package com.soraban.readiness.transmission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soraban.readiness.transmission.domain.AttentionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Raises and resolves the things a person has to look at.
 *
 * <h2>Attention is orthogonal to state, and that is the point</h2>
 *
 * <p>The brief asks for two things that pull against each other: every filing in exactly one
 * state, <em>and</em> "we stopped retrying" surfaced to a human. Modelling the second as a
 * state forces a choice between knowing <b>where</b> a filing is and knowing <b>why</b>
 * someone is needed.
 *
 * <p>So nothing in this class ever changes a filing's state, a batch's state, or a polling
 * schedule. Raising {@link AttentionType#TRANSMISSION_RETRIES_EXHAUSTED} leaves the batch
 * exactly where it was, still scheduled, still polling &mdash; it only means a person should
 * look. That is what lets "we stopped retrying" be visible without being terminal.
 *
 * <h2>Idempotent by constraint</h2>
 *
 * <p>A sweeper runs every 30 seconds over the same conditions. Without the partial unique
 * index on {@code (entity, type) where resolved_at is null}, it would produce thousands of
 * copies of the same unresolved problem and the morning-after page would be unusable by
 * 3 a.m. {@code ON CONFLICT DO NOTHING} against that index makes re-raising free.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class AttentionService {

    private static final Logger log = LoggerFactory.getLogger(AttentionService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AttentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Raises an item, or does nothing if the same one is already open.
     *
     * <p>Must run inside the caller's transaction. Raising an item is part of the fact it
     * describes, not a side effect of it: if the transaction that discovered a rejection
     * rolls back, the attention item must roll back with it, or the page would show a
     * problem the data does not support.
     */
    public void raise(AttentionType type, String entityType, String entityId,
                      Long clientId, Map<String, Object> detail) {
        try {
            jdbc.update("""
                    insert into app.attention_item (
                        firm_id, client_id, entity_type, entity_id, type, severity, detail)
                    values (app.current_firm_id(), ?, ?, ?, ?, ?, ?::jsonb)
                    on conflict do nothing
                    """,
                    clientId, entityType, entityId, type.name(), type.severity(),
                    json.writeValueAsString(detail == null ? Map.of() : detail));

            if (type.isAlarming()) {
                // Severity 0-1 means a possible correctness failure rather than ordinary
                // work. Logged at WARN so it is visible without reading the page.
                log.warn("attention type={} entity={}:{} severity={} detail={}",
                        type, entityType, entityId, type.severity(), detail);
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed raising attention item " + type, e);
        }
    }

    /**
     * Resolves an open item of this type for this entity, if one exists.
     *
     * <p>Called in the <b>same transaction</b> that clears the underlying condition. An
     * acknowledgment that arrives resolves the "unacknowledged too long" item atomically
     * with applying the acks, so the page can never show a stale warning about a batch that
     * has already settled.
     */
    public void resolve(AttentionType type, String entityType, String entityId, String resolvedBy) {
        jdbc.update("""
                update app.attention_item
                   set resolved_at = clock_timestamp(), resolved_by = ?
                 where entity_type = ? and entity_id = ? and type = ? and resolved_at is null
                """, resolvedBy, entityType, entityId, type.name());
    }

    /** Resolves several types at once, for the common "this batch settled" case. */
    public void resolveAll(String entityType, String entityId, String resolvedBy,
                           AttentionType... types) {
        for (AttentionType type : types) {
            resolve(type, entityType, entityId, resolvedBy);
        }
    }

    /**
     * Flags batches that have been waiting too long.
     *
     * <p>Splits into two types on the batch's state, and the split is genuinely useful rather
     * than pedantic: {@code DISPATCHED} means we never received a receipt, so failure mode B
     * may have fired and the IRS's holdings are unknown. {@code SUBMITTED} means the IRS
     * confirmed intake and is merely slow. Those are different problems, and the first sorts
     * above the second on the morning-after page.
     *
     * <p>Note what this does <b>not</b> do: it does not touch {@code next_action_at}, does not
     * change state, and does not stop anything. Polling continues on exactly the schedule it
     * was already on.
     */
    public int sweepUnacknowledged(java.time.Duration threshold) {
        Integer raised = jdbc.update("""
                insert into app.attention_item (
                    firm_id, client_id, entity_type, entity_id, type, severity, detail)
                select app.current_firm_id(), b.client_id, 'BATCH', b.id::text,
                       case b.state when 'DISPATCHED' then 'SUBMISSION_INDETERMINATE_TOO_LONG'
                                    else 'SUBMISSION_UNACKNOWLEDGED_TOO_LONG' end,
                       case b.state when 'DISPATCHED' then 1 else 4 end,
                       jsonb_build_object(
                         'firstDispatchAt', b.first_dispatch_at,
                         'attempts', b.attempt_count,
                         'polls', b.poll_count,
                         'filings', b.filing_count,
                         'nextPollAt', b.next_action_at,
                         'lastError', b.last_error_class)
                  from app.filing_batch b
                 where b.state in ('DISPATCHED', 'SUBMITTED')
                   and b.first_dispatch_at < clock_timestamp() - (? || ' milliseconds')::interval
                on conflict do nothing
                """, threshold.toMillis());
        return raised == null ? 0 : raised;
    }

    /** Open items, most urgent first. Feeds the morning-after page. */
    public java.util.List<Map<String, Object>> openItems() {
        return jdbc.queryForList("""
                select a.type, a.severity, a.entity_type, a.entity_id, a.client_id,
                       c.legal_name as client_name, a.detail, a.first_seen_at
                  from app.attention_item a
                  left join app.client c on c.id = a.client_id
                 where a.resolved_at is null
                 order by a.severity, a.first_seen_at
                """);
    }

    /** Counts by type, for the page's summary tiles. */
    public java.util.List<Map<String, Object>> openCountsByType() {
        return jdbc.queryForList("""
                select type, severity, count(*) as count, min(first_seen_at) as oldest
                  from app.attention_item
                 where resolved_at is null
                 group by type, severity
                 order by severity, count(*) desc
                """);
    }
}
