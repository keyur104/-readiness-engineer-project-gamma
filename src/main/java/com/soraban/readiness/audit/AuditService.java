package com.soraban.readiness.audit;

import com.soraban.readiness.config.ConditionalOnDatabase;
import com.soraban.readiness.security.FirmContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Appends hash-chained audit events, and verifies the chain.
 *
 * <h2>Buffered to commit, on purpose</h2>
 *
 * <p>An event describes something that <em>happened</em>. If the transaction that did it
 * rolls back, it did not happen, and an audit log recording it would be worse than one that
 * missed it &mdash; a false record is the failure mode an auditor cannot recover from,
 * because nothing downstream contradicts it.
 *
 * <p>So calls to {@link #record} inside a transaction buffer into that transaction and flush
 * at {@code beforeCommit}. That also means the chain-head row lock is taken once, at the very
 * end, and held for microseconds &mdash; rather than at the start of a filing run that then
 * holds it for twenty seconds and serialises every other writer in the firm behind it.
 *
 * <h2>Except on the failure path, which is exactly when you want the record</h2>
 *
 * <p>{@link #recordSurvivingRollback} writes on its own connection in its own transaction, so
 * the event outlives the rollback that produced it. "A person tried to force a transition and
 * it was refused" is precisely the entry worth keeping, and buffering it into the doomed
 * transaction would discard it.
 *
 * <h2>The hash is computed here, not in SQL</h2>
 *
 * <p>One implementation, used by both the writer and the verifier. A SQL-side hash would be a
 * second implementation of the canonical form, and the failure mode of the two disagreeing is
 * a chain that verifies as broken when nothing is wrong &mdash; which trains people to ignore
 * the verifier.
 */
@Service
@ConditionalOnDatabase
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Fixed anchor, matching the genesis row seeded by {@code V10__audit.sql}. */
    static final byte[] GENESIS = sha256("readiness:audit:genesis:v1".getBytes(StandardCharsets.UTF_8));

    private static final String BUFFER_KEY = AuditService.class.getName() + ".buffer";

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    /**
     * Configured to sort map entries at every level, so the canonical form is a function of
     * the CONTENT and not of anyone's iteration order -- including a nested map's.
     */
    private final ObjectMapper json = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public AuditService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /** One event, before it has a sequence number or a place in the chain. */
    public record Event(String actor, String actorRole, String action,
                        String entityType, String entityId, Map<String, Object> detail) {

        public static Event system(String component, String action,
                                   String entityType, String entityId, Map<String, Object> detail) {
            // "system:" is a prefix rather than a null actor, so an unattributed event cannot
            // be written by forgetting a parameter -- the column is NOT NULL with no default.
            return new Event("system:" + component, null, action, entityType, entityId, detail);
        }

        public static Event human(String login, String role, String action,
                                  String entityType, String entityId, Map<String, Object> detail) {
            return new Event(login, role, action, entityType, entityId, detail);
        }
    }

    // =================================================================================
    // Appending
    // =================================================================================

    /**
     * Records an event, flushed when the current transaction commits.
     *
     * <p>Outside a transaction it appends immediately, which is correct for CLI-level events
     * ("a filing run started") that are not part of any unit of work.
     */
    public void record(Event event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            appendNow(FirmContext.require(), List.of(event));
            return;
        }

        @SuppressWarnings("unchecked")
        List<Event> buffer = (List<Event>) TransactionSynchronizationManager.getResource(BUFFER_KEY);

        if (buffer == null) {
            buffer = new ArrayList<>();
            TransactionSynchronizationManager.bindResource(BUFFER_KEY, buffer);

            long firmId = FirmContext.require();
            List<Event> pending = buffer;

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    // Inside the committing transaction, so the events and the work they
                    // describe are atomic: there is no window in which one exists without
                    // the other, in either direction.
                    //
                    // Copy-then-clear rather than letting the append drain the list: the
                    // append is also called with an immutable List.of(...) from the
                    // non-transactional path, and having it mutate its argument made that
                    // path throw UnsupportedOperationException. Ownership of the buffer
                    // belongs here, where the buffer is.
                    List<Event> flushing = List.copyOf(pending);
                    pending.clear();
                    appendInCurrentTransaction(firmId, flushing);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(BUFFER_KEY);
                    if (status == STATUS_ROLLED_BACK && !pending.isEmpty()) {
                        // Deliberately only a log line. These events describe work that did
                        // not happen; persisting them would put a false statement in the one
                        // record that exists to be trusted.
                        log.debug("audit: discarded {} buffered event(s) on rollback", pending.size());
                    }
                }
            });
        }

        buffer.add(event);
    }

    /**
     * Records an event on its own transaction, so it survives the rollback of the caller's.
     *
     * <p>For refusals and failures &mdash; the entries whose whole value is that they exist
     * after something went wrong.
     */
    public void recordSurvivingRollback(long firmId, Event event) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("audit:out-of-band");
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        FirmContext.runAs(firmId, () -> new TransactionTemplate(transactionManager, definition)
                .execute(status -> {
                    appendInCurrentTransaction(firmId, List.of(event));
                    return null;
                }));
    }

    private void appendNow(long firmId, List<Event> events) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("audit:append");
        new TransactionTemplate(transactionManager, definition).execute(status -> {
            appendInCurrentTransaction(firmId, events);
            return null;
        });
    }

    /**
     * The append itself. Must run inside a transaction that already has firm context.
     *
     * <p>{@code for update} on the head row is what makes the sequence correct under
     * concurrency. Reading {@code max(seq)} instead is a check-then-act race that two writers
     * both win at {@code READ COMMITTED}; the unique constraint would catch it, but as a
     * failed transaction rather than as a correctly ordered append.
     */
    private void appendInCurrentTransaction(long firmId, List<Event> events) {
        if (events.isEmpty()) {
            return;
        }

        Map<String, Object> head = jdbc.queryForMap(
                "select seq, hash from app.audit_chain_head where firm_id = ? for update", firmId);

        long seq = ((Number) head.get("seq")).longValue();
        byte[] previous = (byte[]) head.get("hash");

        for (Event event : events) {
            seq++;
            String detail = canonicalJson(event.detail());
            byte[] hash = chain(previous, firmId, seq, event, detail);

            jdbc.update("""
                    insert into app.audit_event
                        (firm_id, seq, actor, actor_role, action,
                         entity_type, entity_id, detail, prev_hash, hash)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """,
                    firmId, seq, event.actor(), event.actorRole(), event.action(),
                    event.entityType(), event.entityId(), detail, previous, hash);

            previous = hash;
        }

        jdbc.update("""
                update app.audit_chain_head
                   set seq = ?, hash = ?, updated_at = clock_timestamp()
                 where firm_id = ?
                """, seq, previous, firmId);
    }

    // =================================================================================
    // Verifying
    // =================================================================================

    /** What a verification pass found. */
    public record Verification(long firmId, long events, boolean intact,
                               String headHex, List<String> problems) {
    }

    /**
     * Recomputes the whole chain and compares it, link by link, against what is stored.
     *
     * <p>Checks three separate things, because they fail in three different ways:
     * <ul>
     *   <li>each event's hash matches a recomputation from its own contents &mdash; catches
     *       an edited row;</li>
     *   <li>each event's {@code prev_hash} equals the previous event's {@code hash} &mdash;
     *       catches a removed or inserted row;</li>
     *   <li>the sequence has no gaps and the head matches the last event &mdash; catches a
     *       truncation at the end, which the link check alone would accept.</li>
     * </ul>
     *
     * <p>Deliberately streams in {@code seq} order rather than loading the table: this has to
     * work in the year the table is large, and a verifier that runs out of memory is a
     * verifier nobody runs.
     */
    public Verification verify(long firmId) {
        // Opens its own read-only transaction rather than requiring the caller to.
        // Firm context is applied at transaction start, so a bare query would have no
        // app.current_firm_id and would raise 28000 -- and "verify" is precisely the
        // operation a caller reaches for when something is already wrong, which is the
        // worst moment to hand them a confusing error about transaction scope.
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("audit:verify");
        definition.setReadOnly(true);
        return FirmContext.runAs(firmId, () ->
                new TransactionTemplate(transactionManager, definition)
                        .execute(status -> verifyInTransaction(firmId)));
    }

    private Verification verifyInTransaction(long firmId) {
        List<String> problems = new ArrayList<>();

        Map<String, Object> head = jdbc.queryForMap(
                "select seq, hash from app.audit_chain_head where firm_id = ?", firmId);
        long headSeq = ((Number) head.get("seq")).longValue();
        byte[] headHash = (byte[]) head.get("hash");

        var state = new Object() {
            byte[] previous = GENESIS;
            long expectedSeq = 0;
            long count = 0;
        };

        jdbc.query("""
                select seq, actor, actor_role, action, entity_type, entity_id,
                       detail::text as detail_text, prev_hash, hash
                  from app.audit_event
                 where firm_id = ?
                 order by seq
                """, rs -> {
            long seq = rs.getLong("seq");
            state.expectedSeq++;
            state.count++;

            if (seq != state.expectedSeq) {
                problems.add("sequence gap: expected %d, found %d -- %d event(s) are missing"
                        .formatted(state.expectedSeq, seq, seq - state.expectedSeq));
                state.expectedSeq = seq;
            }

            byte[] storedPrev = rs.getBytes("prev_hash");
            byte[] storedHash = rs.getBytes("hash");

            if (!MessageDigest.isEqual(storedPrev, state.previous)) {
                problems.add("broken link at seq %d: prev_hash does not match the preceding event"
                        .formatted(seq));
            }

            Event event = new Event(rs.getString("actor"), rs.getString("actor_role"),
                    rs.getString("action"), rs.getString("entity_type"),
                    rs.getString("entity_id"), Map.of());
            // Re-canonicalise rather than hashing what Postgres handed back verbatim.
            // jsonb is a parsed representation, not the text that was inserted: it sorts
            // keys, drops duplicates, and renders with its own spacing. Hashing detail::text
            // directly made every chain verify as broken -- the writer had hashed Jackson's
            // output and the verifier was hashing Postgres's. Round-tripping both sides
            // through the same serialiser makes the canonical form depend on the content.
            byte[] recomputed = chain(storedPrev, firmId, seq, event,
                    canonicalJson(rs.getString("detail_text")));

            if (!MessageDigest.isEqual(storedHash, recomputed)) {
                problems.add("altered content at seq %d: the row does not hash to its stored value"
                        .formatted(seq));
            }

            state.previous = storedHash;
        }, firmId);

        if (state.count != headSeq) {
            // The check the link chain cannot make. Deleting the tail leaves every remaining
            // link valid; only the head knows how long the chain was supposed to be.
            problems.add("head says %d event(s), found %d -- the chain has been truncated"
                    .formatted(headSeq, state.count));
        }

        if (state.count > 0 && !MessageDigest.isEqual(state.previous, headHash)) {
            problems.add("head hash does not match the last event");
        }

        return new Verification(firmId, state.count, problems.isEmpty(),
                HexFormat.of().formatHex(headHash), problems);
    }

    // =================================================================================
    // The canonical form
    // =================================================================================

    /**
     * {@code sha256(prev_hash ‖ firm ‖ seq ‖ actor ‖ role ‖ action ‖ type ‖ id ‖ detail)}.
     *
     * <p>Every field is length-prefixed. Without that, {@code ("ab", "c")} and
     * {@code ("a", "bc")} hash identically, so two different events could be swapped for one
     * another without breaking the chain &mdash; and the swap is exactly what an attacker
     * would want.
     *
     * <p>{@code occurred_at} is deliberately <b>not</b> in the hash. It is a server clock
     * value read at insert, and including it would mean a verifier has to reproduce the
     * database's timestamp formatting exactly, in Java, forever. A changed timestamp is
     * detectable anyway: it cannot be changed without an UPDATE, and UPDATE is denied by a
     * grant, by the absence of a policy, and by a trigger.
     */
    private byte[] chain(byte[] previous, long firmId, long seq, Event event, String detailJson) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        buffer.writeBytes(previous);
        feed(buffer, Long.toString(firmId));
        feed(buffer, Long.toString(seq));
        feed(buffer, event.actor());
        feed(buffer, event.actorRole());
        feed(buffer, event.action());
        feed(buffer, event.entityType());
        feed(buffer, event.entityId());
        feed(buffer, detailJson);
        return sha256(buffer.toByteArray());
    }

    private static void feed(ByteArrayOutputStream buffer, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        // A null and an empty string must not collide: the length prefix is preceded by a
        // presence byte, so "absent" and "present but empty" hash differently.
        buffer.write(value == null ? 0 : 1);
        buffer.write((bytes.length >>> 24) & 0xFF);
        buffer.write((bytes.length >>> 16) & 0xFF);
        buffer.write((bytes.length >>> 8) & 0xFF);
        buffer.write(bytes.length & 0xFF);
        buffer.writeBytes(bytes);
    }

    /**
     * Sorted keys, so the JSON a verifier hashes is the JSON the writer hashed.
     *
     * <p>Postgres normalises {@code jsonb} on the way in &mdash; it sorts keys and drops
     * duplicates &mdash; so hashing the map's arbitrary iteration order would produce a value
     * that never verifies after a round trip. Sorting here matches what comes back out.
     */
    /** Re-canonicalises JSON text that has been through Postgres. */
    private String canonicalJson(String jsonText) {
        try {
            if (jsonText == null || jsonText.isBlank()) {
                return canonicalJson(Map.<String, Object>of());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(jsonText, Map.class);
            return canonicalJson(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("stored audit detail is not valid JSON", e);
        }
    }

    private String canonicalJson(Map<String, Object> detail) {
        try {
            SortedMap<String, Object> sorted = new TreeMap<>(detail == null ? Map.of() : detail);
            return json.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalArgumentException("audit detail is not serialisable", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
