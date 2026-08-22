package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.security.Tin;
import com.soraban.readiness.security.TinCryptoService;
import com.soraban.readiness.transmission.domain.AttentionType;
import com.soraban.readiness.transmission.domain.BatchState;
import com.soraban.readiness.transmission.domain.FilingState;
import com.soraban.readiness.transmission.domain.IdempotencyKey;
import com.soraban.readiness.transmission.ratelimit.FirmRateBudget;
import com.soraban.readiness.transmission.spi.IrsTransmissionClient;
import com.soraban.readiness.transmission.spi.TransmissionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * T2 and T3 &mdash; dispatch a sealed batch and record what came back.
 *
 * <h2>The transaction boundaries, which are the entire design</h2>
 *
 * <pre>
 *   T2  DISPATCH INTENT    [tx] ──&gt; commit      &lt;-- the write-ahead barrier
 *       HTTP POST /submit  [NO tx, NO pooled connection]
 *   T3  RECORD OUTCOME     [tx] ──&gt; commit
 * </pre>
 *
 * <p>T2 moves the batch to {@code DISPATCHED} and its filings to
 * {@code SUBMITTED_UNACKNOWLEDGED} <b>before the call is made</b>. Stated plainly:
 * <b>we declare the filings possibly-live before we make them possibly-live.</b>
 *
 * <p>After T2 commits, no code path anywhere can send this content under a different key
 * without first obtaining positive proof of non-delivery. A 500, a socket reset, a timeout,
 * and a hard process kill all leave exactly the same durable state &mdash; which is correct,
 * because all four are equally uninformative about what the server did.
 *
 * <h2>No database connection is held across the HTTP call</h2>
 *
 * <p>This is the classic way a correct-looking design dies in production: twenty concurrent
 * thirty-second calls, each holding a pooled connection, and the pool is exhausted while the
 * database sits idle. Splitting T2 and T3 is what avoids it. The advisory lock the rate
 * limiter takes is likewise released at T2's commit, before the call &mdash; holding it would
 * serialize an entire firm behind one in-flight request.
 *
 * <h2>An error never releases filings</h2>
 *
 * <p>T3 has no path that moves filings backwards. Only {@code ReconciliationService},
 * holding a {@link IrsTransmissionClient.StatusResult.Unknown}, may do that &mdash; and it
 * bumps the generation when it does.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class BatchDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BatchDispatcher.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final IrsTransmissionClient irs;
    private final FirmRateBudget rateBudget;
    private final TinCryptoService tinCrypto;
    private final AttentionService attention;
    private final CrashHooks crashHooks;

    private final int maxAttempts;
    private final Duration initialPollDelay;
    private final Duration maxInterval;
    private final double backoffMultiplier;

    private final String workerId = "worker-" + ProcessHandle.current().pid();

    public BatchDispatcher(JdbcTemplate jdbc,
                           PlatformTransactionManager transactionManager,
                           IrsTransmissionClient irs,
                           FirmRateBudget rateBudget,
                           TinCryptoService tinCrypto,
                           AttentionService attention,
                           CrashHooks crashHooks,
                           @org.springframework.beans.factory.annotation.Value("${irs.max-attempts:5}")
                           int maxAttempts,
                           @org.springframework.beans.factory.annotation.Value("${irs.poll.initial-delay:10s}")
                           Duration initialPollDelay,
                           @org.springframework.beans.factory.annotation.Value("${irs.poll.max-interval:15m}")
                           Duration maxInterval,
                           @org.springframework.beans.factory.annotation.Value("${irs.poll.backoff-multiplier:2.0}")
                           double backoffMultiplier) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.irs = irs;
        this.rateBudget = rateBudget;
        this.tinCrypto = tinCrypto;
        this.attention = attention;
        this.crashHooks = crashHooks;
        this.maxAttempts = maxAttempts;
        this.initialPollDelay = initialPollDelay;
        this.maxInterval = maxInterval;
        this.backoffMultiplier = backoffMultiplier;
    }

    /** What T2 produced, carried across the transaction boundary to the call. */
    private record DispatchIntent(
            UUID batchId, long clientId, int taxYear, String idempotencyKey,
            int attemptNo, long callLogId, List<IrsTransmissionClient.FilingPayload> payloads) {
    }

    // =================================================================================
    // T2 -- dispatch intent
    // =================================================================================

    /**
     * Claims one due batch and commits the intent to send it.
     *
     * @return the intent to execute, or empty when nothing is due or the budget is spent
     */
    private Optional<DispatchIntent> prepareDispatch(long firmId, UUID batchId) {
        return inTransaction("transmission:dispatch-intent", () -> {

            Map<String, Object> batch = claimBatch(batchId);
            if (batch == null) {
                return Optional.<DispatchIntent>empty();
            }

            String storedKey = (String) batch.get("idempotency_key");
            long clientId = ((Number) batch.get("client_id")).longValue();
            int taxYear = ((Number) batch.get("tax_year")).intValue();
            int attemptNo = ((Number) batch.get("attempt_count")).intValue() + 1;

            List<Map<String, Object>> members = jdbc.queryForList("""
                    select m.filing_id, m.filing_generation, m.content_hash,
                           f.recipient_name, f.recipient_tin_ct, f.recipient_tin_bidx,
                           f.amount_cents, f.withholding_cents, f.client_id
                      from app.filing_batch_member m
                      join app.filing f on f.id = m.filing_id
                     where m.batch_id = ?
                     order by m.filing_id
                    """, batchId);

            // Cheap paranoia with real teeth: recompute the key from the FROZEN membership
            // and compare. A mismatch means something mutated a filing that is already
            // committed to a batch, so we would ship one number under a key derived from
            // another. Refuse rather than reconcile.
            String recomputed = IdempotencyKey.batchKey(firmId, clientId, taxYear,
                    members.stream()
                            .map(m -> new IdempotencyKey.Member(
                                    (UUID) m.get("filing_id"),
                                    ((Number) m.get("filing_generation")).intValue(),
                                    (byte[]) m.get("content_hash")))
                            .toList());

            if (!recomputed.equals(storedKey)) {
                attention.raise(AttentionType.AMENDED_DATA_FOR_INFLIGHT_FILING,
                        "BATCH", batchId.toString(), clientId,
                        Map.of("storedKey", storedKey, "recomputedKey", recomputed));
                jdbc.update("update app.filing_batch set lease_owner = null, "
                            + "next_action_at = clock_timestamp() + interval '1 hour' where id = ?",
                        batchId);
                return Optional.<DispatchIntent>empty();
            }

            // The rate token is consumed IN THIS TRANSACTION, so it commits atomically with
            // the state change below. If this transaction rolls back, the token rolls back
            // with it -- there is no ordering that over-spends the budget.
            FirmRateBudget.Admission admission =
                    rateBudget.tryAdmit(firmId, "SUBMIT", batchId, workerId);

            if (admission instanceof FirmRateBudget.Admission.Refused refused) {
                jdbc.update("""
                        update app.filing_batch
                           set next_action_at = ?, lease_owner = null, lease_expires_at = null
                         where id = ?
                        """, java.sql.Timestamp.from(refused.retryAt()), batchId);
                return Optional.<DispatchIntent>empty();
            }

            long callLogId = ((FirmRateBudget.Admission.Granted) admission).callLogId();

            jdbc.update("""
                    insert into app.transmission_attempt (
                        firm_id, batch_id, attempt_no, call_type, idempotency_key, worker_id)
                    values (app.current_firm_id(), ?, ?, 'SUBMIT', ?, ?)
                    """, batchId, attemptNo, storedKey, workerId);

            jdbc.update("""
                    update app.filing_batch
                       set state = ?, attempt_count = ?,
                           first_dispatch_at = coalesce(first_dispatch_at, clock_timestamp()),
                           lease_owner = ?, lease_expires_at = clock_timestamp() + interval '5 minutes',
                           next_action_at = clock_timestamp() + interval '2 minutes'
                     where id = ?
                    """, BatchState.DISPATCHED.name(), attemptNo, workerId, batchId);

            // THE line. Filings become possibly-live before the call is made.
            jdbc.update("""
                    update app.filing
                       set state = ?, state_changed_at = clock_timestamp()
                     where id in (select filing_id from app.filing_batch_member where batch_id = ?)
                       and state = ?
                    """, FilingState.SUBMITTED_UNACKNOWLEDGED.name(), batchId, FilingState.BATCHED.name());

            crashHooks.reached(CrashHooks.CrashPoint.BEFORE_DISPATCH_COMMIT);

            return Optional.of(new DispatchIntent(
                    batchId, clientId, taxYear, storedKey, attemptNo, callLogId,
                    buildPayloads(firmId, members)));
        });
    }

    /** Claims the batch row, or returns null if another worker holds a live lease. */
    private Map<String, Object> claimBatch(UUID batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, client_id, tax_year, idempotency_key, state, attempt_count
                  from app.filing_batch
                 where id = ?
                   and state in ('SEALED', 'DISPATCHED')
                   and (lease_expires_at is null or lease_expires_at < clock_timestamp())
                   for update skip locked
                """, batchId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Decrypts TINs and builds the wire payload.
     *
     * <p>One of exactly two places plaintext exists. {@code clientReference} carries our own
     * filing id, which is what every acknowledgment is correlated by &mdash; never array
     * position, which silently corrupts the moment a server reorders or coalesces entries.
     */
    private List<IrsTransmissionClient.FilingPayload> buildPayloads(
            long firmId, List<Map<String, Object>> members) {

        List<IrsTransmissionClient.FilingPayload> payloads = new ArrayList<>(members.size());
        for (Map<String, Object> member : members) {
            long clientId = ((Number) member.get("client_id")).longValue();
            byte[] ciphertext = (byte[]) member.get("recipient_tin_ct");
            byte[] blindIndex = (byte[]) member.get("recipient_tin_bidx");

            String tin = null;
            if (ciphertext != null && blindIndex != null) {
                Tin decrypted = tinCrypto.decrypt(firmId, clientId, blindIndex, ciphertext, 1);
                tin = decrypted.plaintextForTransmission();
            }

            payloads.add(new IrsTransmissionClient.FilingPayload(
                    member.get("filing_id").toString(),
                    ((Number) member.get("filing_generation")).intValue(),
                    "PAYER-EIN",
                    tin,
                    "EIN",
                    (String) member.get("recipient_name"),
                    ((Number) member.get("amount_cents")).longValue(),
                    ((Number) member.get("withholding_cents")).longValue()));
        }
        return payloads;
    }

    // =================================================================================
    // The call, and T3
    // =================================================================================

    /**
     * Dispatches one batch end to end.
     *
     * @return true when a call was actually made (whatever its outcome)
     */
    public boolean dispatch(long firmId, UUID batchId) {
        return FirmContext.runAs(firmId, () -> {
            Optional<DispatchIntent> maybeIntent = prepareDispatch(firmId, batchId);
            if (maybeIntent.isEmpty()) {
                return false;
            }
            DispatchIntent intent = maybeIntent.get();

            crashHooks.reached(CrashHooks.CrashPoint.AFTER_DISPATCH_COMMIT_BEFORE_CALL);

            // No transaction and no pooled connection is held from here until T3.
            IrsTransmissionClient.Receipt receipt = null;
            TransmissionExceptions.TransmissionException failure = null;
            try {
                crashHooks.reached(CrashHooks.CrashPoint.DURING_CALL);
                receipt = irs.submit(new IrsTransmissionClient.SubmitRequest(
                        firmId, intent.clientId(), intent.taxYear(),
                        intent.idempotencyKey(), intent.payloads()));
            } catch (TransmissionExceptions.TransmissionException e) {
                failure = e;
            } catch (RuntimeException e) {
                // Anything unanticipated is Indeterminate. Unknown implies indeterminate --
                // the safe direction, and the reason the default matters.
                failure = new TransmissionExceptions.Indeterminate(
                        "unexpected failure during submit", e);
            }

            crashHooks.reached(CrashHooks.CrashPoint.AFTER_CALL_BEFORE_OUTCOME_COMMIT);
            recordOutcome(intent, receipt, failure);
            return true;
        });
    }

    /** T3 &mdash; persist what happened. */
    private void recordOutcome(DispatchIntent intent,
                               IrsTransmissionClient.Receipt receipt,
                               TransmissionExceptions.TransmissionException failure) {
        inTransaction("transmission:record-outcome", () -> {
            if (receipt != null) {
                rateBudget.recordOutcome(intent.callLogId(), "RECEIPT");
                finishAttempt(intent, "RECEIPT", null, null, receipt.receiptId());

                jdbc.update("""
                        update app.filing_batch
                           set state = ?, receipt_id = ?, submitted_at = clock_timestamp(),
                               lease_owner = null, lease_expires_at = null,
                               next_action_at = clock_timestamp() + (? || ' milliseconds')::interval,
                               last_error_class = null, last_error_detail = null
                         where id = ?
                        """, BatchState.SUBMITTED.name(), receipt.receiptId(),
                        initialPollDelay.toMillis(), intent.batchId());

                // Filings deliberately STAY SubmittedUnacknowledged. A receipt is evidence
                // about the batch's intake, not about any individual filing's acceptance.
                return null;
            }

            String errorClass = failure.getClass().getSimpleName();
            rateBudget.recordOutcome(intent.callLogId(), "ERROR:" + errorClass);
            finishAttempt(intent, "ERROR", errorClass, failure.getMessage(), null);

            if (failure instanceof TransmissionExceptions.RateLimited) {
                // Unreachable if our accounting is right, so treat it as a bug in us.
                attention.raise(AttentionType.RATE_BUDGET_BREACH_DETECTED,
                        "BATCH", intent.batchId().toString(), intent.clientId(),
                        Map.of("message", String.valueOf(failure.getMessage())));
            }

            // RELEASING A BATCH ON ERROR IS ONLY SAFE ON THE FIRST ATTEMPT.
            //
            // serverMayHaveRecorded() answers "did THIS call reach the server?" -- it says
            // nothing about whether an EARLIER attempt did. On a retry those are completely
            // different questions, and conflating them is a duplicate-filing bug:
            //
            //   attempt 1  submit -> failure mode B: the IRS records all 20 filings and
            //                        returns an error. Batch stays DISPATCHED. Correct.
            //   attempt 2  submit -> refused locally (rate limit / connection refused).
            //                        serverMayHaveRecorded() is false for THIS call...
            //                        ...so the batch would be released back to SEALED and its
            //                        filings back to READY_TO_TRANSMIT -- while the IRS is
            //                        still holding them. They would then be re-planned into a
            //                        NEW batch with a NEW key and submitted a second time.
            //
            // Found by the real-SIGKILL test, which produced exactly that state: a SEALED
            // batch whose 20 filings were already recorded at the endpoint.
            //
            // attemptNo == 1 is the only case where "nothing has ever left" is provable.
            // Beyond that, only reconciliation holding a StatusResult.Unknown may release --
            // which is what the design said all along, and what this code was not doing.
            boolean neverDispatchedBefore = intent.attemptNo() == 1;

            if (!failure.serverMayHaveRecorded() && neverDispatchedBefore) {
                // Provably nothing sent, on the only attempt ever made.
                jdbc.update("""
                        update app.filing_batch
                           set state = ?, lease_owner = null, lease_expires_at = null,
                               next_action_at = clock_timestamp() + (? || ' milliseconds')::interval,
                               last_error_class = ?, last_error_detail = ?
                         where id = ?
                        """, BatchState.SEALED.name(), backoffMillis(intent.attemptNo()),
                        errorClass, failure.getMessage(), intent.batchId());

                jdbc.update("""
                        update app.filing
                           set state = ?, state_changed_at = clock_timestamp()
                         where id in (select filing_id from app.filing_batch_member where batch_id = ?)
                           and state = ?
                        """, FilingState.BATCHED.name(), intent.batchId(),
                        FilingState.SUBMITTED_UNACKNOWLEDGED.name());
                return null;
            }

            if (!failure.serverMayHaveRecorded()) {
                // A later attempt was refused before leaving. The batch STAYS DISPATCHED,
                // because an earlier attempt may already have been recorded and only the
                // endpoint can settle that. Costs one status call; saves a duplicate filing.
                log.debug("batch {} refused locally on attempt {}, but an earlier attempt may "
                          + "have been delivered -- leaving it DISPATCHED for reconciliation",
                        intent.batchId(), intent.attemptNo());
            }

            // Indeterminate. The batch STAYS DISPATCHED and the filings STAY
            // SubmittedUnacknowledged. Only evidence from the IRS moves them.
            jdbc.update("""
                    update app.filing_batch
                       set lease_owner = null, lease_expires_at = null,
                           next_action_at = clock_timestamp() + (? || ' milliseconds')::interval,
                           last_error_class = ?, last_error_detail = ?
                     where id = ?
                    """, backoffMillis(intent.attemptNo()), errorClass,
                    failure.getMessage(), intent.batchId());

            if (intent.attemptNo() >= maxAttempts) {
                // "We stopped retrying" -- an attention item, NOT a state change and NOT a
                // stop. next_action_at above already scheduled the next poll; the batch keeps
                // going at the slow cadence until a human intervenes.
                attention.raise(AttentionType.TRANSMISSION_RETRIES_EXHAUSTED,
                        "BATCH", intent.batchId().toString(), intent.clientId(),
                        Map.of("attempts", intent.attemptNo(),
                               "lastError", errorClass,
                               "note", "polling continues; this is not terminal"));
            }
            return null;
        });
    }

    private void finishAttempt(DispatchIntent intent, String outcome, String errorClass,
                               String errorDetail, String receiptId) {
        jdbc.update("""
                update app.transmission_attempt
                   set finished_at = clock_timestamp(), outcome = ?,
                       error_class = ?, error_detail = ?, receipt_id = ?
                 where batch_id = ? and call_type = 'SUBMIT' and attempt_no = ?
                """, outcome, errorClass, errorDetail, receiptId,
                intent.batchId(), intent.attemptNo());
    }

    /**
     * Exponential backoff with <b>full jitter</b>, capped.
     *
     * <p>Jitter is not decoration. Five hundred batches dispatched in the same minute would
     * otherwise all become due in the same second; the limiter would refuse 480 of them, and
     * each refusal would reschedule to the same instant &mdash; a synchronized retry storm
     * that spends the entire budget on refusals and makes no progress at all.
     *
     * <p>The interval is capped; the <em>count</em> is not. The brief says acknowledgment
     * takes "minutes to hours, occasionally never" and that the design should not care which,
     * so a batch polls forever at the slow cadence rather than being abandoned.
     */
    private long backoffMillis(int attemptNo) {
        double base = initialPollDelay.toMillis() * Math.pow(backoffMultiplier, attemptNo - 1);
        long capped = (long) Math.min(base, maxInterval.toMillis());
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Math.max(2, capped));
    }

    private <T> T inTransaction(String name, Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
