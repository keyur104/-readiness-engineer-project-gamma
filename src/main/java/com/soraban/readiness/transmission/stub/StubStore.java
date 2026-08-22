package com.soraban.readiness.transmission.stub;

import com.soraban.readiness.transmission.spi.IrsTransmissionClient.FilingPayload;
import com.soraban.readiness.transmission.spi.IrsTransmissionClient.SubmitRequest;
import com.soraban.readiness.config.ConditionalOnDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fake IRS's persistence, in its own bean.
 *
 * <h2>Why this is a separate class rather than methods on {@link StubIrsClient}</h2>
 *
 * <p>Spring's {@code @Transactional} works by proxying the bean. A call from one method of a
 * class to another method of the <em>same instance</em> never passes through that proxy, so
 * the annotation is silently inert &mdash; the method runs in whatever transaction the caller
 * already had, or none.
 *
 * <p>Here that would not be a cosmetic problem. The stub models an <b>external system</b>: its
 * books must commit independently of ours. If {@code recordSubmission} joined the caller's
 * transaction, then a caller that rolled back would un-record filings the "IRS" had already
 * accepted &mdash; and failure mode B, where the filings are live precisely <em>because</em>
 * the caller's view of events is wrong, would become unrepresentable. The test suite would
 * then be unable to produce the scenario the brief calls the most important line in the
 * document.
 *
 * <p>Extracting to a distinct bean makes {@code REQUIRES_NEW} actually take effect.
 */
@Component
@ConditionalOnProperty(name = "irs.client", havingValue = "stub", matchIfMissing = true)
@ConditionalOnDatabase
public class StubStore {

    private final JdbcTemplate jdbc;

    public StubStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a submission and every filing in it, in one transaction of its own.
     *
     * <p>Atomic on purpose: a partially-recorded submission is a state no real endpoint with
     * an idempotency store would expose, and allowing it here would let the system pass tests
     * against a failure mode that cannot actually occur.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSubmission(SubmitRequest request, String receiptId, boolean modeB,
                                 Duration ackDelay, boolean neverAcks,
                                 java.util.function.Function<FilingPayload, String[]> validator) {
        jdbc.update("""
                insert into irs_stub.submission (
                    idempotency_key, firm_id, client_id, tax_year, receipt_id,
                    acks_available_at, never_acks, outcome_returned)
                values (?, ?, ?, ?, ?, clock_timestamp() + (? || ' milliseconds')::interval, ?, ?)
                on conflict (idempotency_key) do nothing
                """,
                request.idempotencyKey(), request.firmId(), request.clientId(), request.taxYear(),
                receiptId, ackDelay.toMillis(), neverAcks, modeB ? "MODE_B_ERROR" : "RECEIPT");

        List<Object[]> rows = new ArrayList<>(request.filings().size());
        for (FilingPayload filing : request.filings()) {
            String[] rejection = validator.apply(filing);
            rows.add(new Object[]{
                    request.idempotencyKey(), filing.clientReference(), filing.generation(),
                    filing.recipientTin(), filing.recipientName(),
                    filing.nonemployeeCompCents(), filing.federalWithheldCents(),
                    rejection == null ? "ACCEPTED" : "REJECTED",
                    rejection == null ? null : rejection[0],
                    rejection == null ? null : rejection[1],
                    rejection == null ? irsRecordId(filing) : null});
        }

        jdbc.batchUpdate("""
                insert into irs_stub.recorded_filing (
                    idempotency_key, client_reference, filing_generation, recipient_tin,
                    recipient_name, amount_cents, withholding_cents,
                    ack, reason_code, reason_text, irs_record_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    /**
     * Logs an inbound call in its own transaction, before anything else happens.
     *
     * <p>Must survive the call throwing, because a call that is not logged is a call the
     * rate-budget assertion cannot see &mdash; and the assertion that we never exceeded 20
     * calls per rolling minute is only meaningful if it counts the ones that failed too.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logCall(long firmId, String callType, String idempotencyKey) {
        jdbc.update("""
                insert into irs_stub.call_log (firm_id, call_type, idempotency_key)
                values (?, ?, ?)
                """, firmId, callType, idempotencyKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Map<String, Object> findSubmission(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        // acks_ready is decided HERE, by the database, rather than by comparing the stored
        // timestamp against Instant.now() in the caller.
        //
        // acks_available_at is written with PostgreSQL's clock_timestamp(). Comparing it to a
        // JVM Instant.now() is a comparison across two clocks that are never guaranteed to
        // agree, and with ack-delay set to 0ms in tests the two events are microseconds apart
        // -- so which side "wins" is a coin flip. That made every dispatch-then-poll test
        // intermittently see Pending instead of an acknowledgment, and a crash-point test that
        // depends on reaching the apply path failed roughly one run in three.
        //
        // This is the same rule the rate limiter states and relies on: the database clock is
        // the only clock. The stub was quietly breaking it.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select idempotency_key, receipt_id, recorded_at, acks_available_at,
                       never_acks, outcome_returned,
                       (acks_available_at <= clock_timestamp()) as acks_ready
                  from irs_stub.submission
                 where idempotency_key = ?
                """, idempotencyKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Map<String, Object>> findAcks(String idempotencyKey) {
        return jdbc.queryForList("""
                select client_reference, ack, irs_record_id, reason_code, reason_text
                  from irs_stub.recorded_filing
                 where idempotency_key = ?
                 order by id
                """, idempotencyKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long callsInWindow(long firmId, long windowMillis) {
        Long count = jdbc.queryForObject("""
                select count(*) from irs_stub.call_log
                 where firm_id = ?
                   and at > clock_timestamp() - ((? || ' milliseconds')::interval)
                """, Long.class, firmId, windowMillis);
        return count == null ? 0 : count;
    }

    private static String irsRecordId(FilingPayload filing) {
        return "IRS-" + Integer.toHexString(filing.clientReference().hashCode()).toUpperCase()
             + "-" + filing.generation();
    }
}
