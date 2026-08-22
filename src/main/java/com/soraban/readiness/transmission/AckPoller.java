package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.transmission.domain.AttentionType;
import com.soraban.readiness.transmission.domain.BatchState;
import com.soraban.readiness.transmission.domain.FilingState;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Asks the IRS what became of a submission, and applies the answer.
 *
 * <h2>Acknowledgments are correlated by our own reference, never by position</h2>
 *
 * <p>Every {@code FilingPayload} carries {@code clientReference = filing_id}, and every ack
 * echoes it back. Matching by array index would be shorter and would work in every test that
 * used a well-behaved stub &mdash; and it would silently corrupt the instant a real server
 * reordered, dropped, or coalesced entries.
 *
 * <p>That failure mode deserves naming: it produces <b>wrong data in the right shape</b>.
 * Amounts would attach to the wrong recipients, every count would reconcile, nothing would
 * throw, and the errors would surface months later as IRS notices sent to contractors about
 * income they never received. For a system whose output is a tax form, there is no worse
 * class of bug.
 *
 * <h2>Applying acks is all-or-nothing</h2>
 *
 * <p>One transaction covers every ack in the batch, the batch's own transition, and the
 * resolution of any attention items it clears. A crash part-way through therefore rolls back
 * entirely and the poll is simply repeated &mdash; status calls are side-effect free, so
 * repeating one is free.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class AckPoller {

    private static final Logger log = LoggerFactory.getLogger(AckPoller.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final IrsTransmissionClient irs;
    private final FirmRateBudget rateBudget;
    private final AttentionService attention;
    private final CrashHooks crashHooks;
    private final Duration maxInterval;
    private final double backoffMultiplier;
    private final Duration initialPollDelay;

    private final String workerId = "worker-" + ProcessHandle.current().pid();

    public AckPoller(JdbcTemplate jdbc,
                     PlatformTransactionManager transactionManager,
                     IrsTransmissionClient irs,
                     FirmRateBudget rateBudget,
                     AttentionService attention,
                     CrashHooks crashHooks,
                     @org.springframework.beans.factory.annotation.Value("${irs.poll.max-interval:15m}")
                     Duration maxInterval,
                     @org.springframework.beans.factory.annotation.Value("${irs.poll.backoff-multiplier:2.0}")
                     double backoffMultiplier,
                     @org.springframework.beans.factory.annotation.Value("${irs.poll.initial-delay:10s}")
                     Duration initialPollDelay) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.irs = irs;
        this.rateBudget = rateBudget;
        this.attention = attention;
        this.crashHooks = crashHooks;
        this.maxInterval = maxInterval;
        this.backoffMultiplier = backoffMultiplier;
        this.initialPollDelay = initialPollDelay;
    }

    /** Polls one batch. Returns true when a call was made. */
    public boolean poll(long firmId, UUID batchId) {
        return FirmContext.runAs(firmId, () -> {

            PollIntent intent = inTransaction("transmission:poll-intent", () -> {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        select id, client_id, idempotency_key, state, poll_count
                          from app.filing_batch
                         where id = ? and state in ('DISPATCHED', 'SUBMITTED')
                           and (lease_expires_at is null or lease_expires_at < clock_timestamp())
                           for update skip locked
                        """, batchId);
                if (rows.isEmpty()) {
                    return null;
                }
                Map<String, Object> batch = rows.getFirst();

                FirmRateBudget.Admission admission =
                        rateBudget.tryAdmit(firmId, "STATUS", batchId, workerId);

                if (admission instanceof FirmRateBudget.Admission.Refused refused) {
                    jdbc.update("update app.filing_batch set next_action_at = ?, lease_owner = null "
                                + "where id = ?",
                            java.sql.Timestamp.from(refused.retryAt()), batchId);
                    return null;
                }

                int pollNo = ((Number) batch.get("poll_count")).intValue() + 1;
                jdbc.update("""
                        update app.filing_batch
                           set poll_count = ?, lease_owner = ?,
                               lease_expires_at = clock_timestamp() + interval '2 minutes'
                         where id = ?
                        """, pollNo, workerId, batchId);

                return new PollIntent(batchId,
                        ((Number) batch.get("client_id")).longValue(),
                        (String) batch.get("idempotency_key"),
                        pollNo,
                        ((FirmRateBudget.Admission.Granted) admission).callLogId());
            });

            if (intent == null) {
                return false;
            }

            crashHooks.reached(CrashHooks.CrashPoint.AFTER_RECEIPT_BEFORE_POLL);

            IrsTransmissionClient.StatusResult result;
            try {
                result = irs.status(IrsTransmissionClient.StatusQuery.byKey(
                        firmId, intent.idempotencyKey()));
            } catch (TransmissionExceptions.TransmissionException e) {
                // Recording the outcome writes to app.irs_call_log, which is firm-scoped, so
                // it needs a transaction of its own -- the poll call itself deliberately ran
                // outside one so no pooled connection was held across the network.
                inTransaction("transmission:poll-outcome", () -> {
                    rateBudget.recordOutcome(intent.callLogId(),
                            "ERROR:" + e.getClass().getSimpleName());
                    return null;
                });
                reschedule(intent.batchId(), intent.pollNo(), e.getClass().getSimpleName());
                return true;
            }

            final IrsTransmissionClient.StatusResult settled = result;
            inTransaction("transmission:poll-outcome", () -> {
                rateBudget.recordOutcome(intent.callLogId(), "OK");
                return null;
            });
            applyStatus(intent, settled);
            return true;
        });
    }

    private record PollIntent(UUID batchId, long clientId, String idempotencyKey,
                              int pollNo, long callLogId) {
    }

    // =================================================================================
    // Applying the answer
    // =================================================================================

    private void applyStatus(PollIntent intent, IrsTransmissionClient.StatusResult result) {
        inTransaction("transmission:apply-status", () -> {
            switch (result) {
                case IrsTransmissionClient.StatusResult.Unknown ignored -> {
                    // POSITIVE PROOF OF NON-DELIVERY. The only path that releases filings
                    // from SUBMITTED_UNACKNOWLEDGED -- and it bumps the generation, so any
                    // later submission is genuinely new rather than a retry under a key the
                    // server has already seen.
                    log.info("batch {} confirmed absent at the endpoint; voiding and releasing "
                             + "its filings with a bumped generation", intent.batchId());

                    jdbc.update("""
                            update app.filing
                               set state = ?, generation = generation + 1,
                                   state_changed_at = clock_timestamp()
                             where id in (select filing_id from app.filing_batch_member where batch_id = ?)
                            """, FilingState.READY_TO_TRANSMIT.name(), intent.batchId());

                    jdbc.update("""
                            update app.filing_batch
                               set state = ?, needs_reconcile = false,
                                   lease_owner = null, lease_expires_at = null
                             where id = ?
                            """, BatchState.VOID.name(), intent.batchId());
                }

                case IrsTransmissionClient.StatusResult.Pending pending -> {
                    // Recorded, acks not yet available. Promote DISPATCHED to SUBMITTED --
                    // we now have a receipt, so the batch's fate is no longer unknown even
                    // though its filings' outcomes still are.
                    jdbc.update("""
                            update app.filing_batch
                               set state = ?, receipt_id = coalesce(receipt_id, ?),
                                   submitted_at = coalesce(submitted_at, clock_timestamp()),
                                   needs_reconcile = false,
                                   lease_owner = null, lease_expires_at = null,
                                   next_action_at = clock_timestamp() + (? || ' milliseconds')::interval
                             where id = ?
                            """, BatchState.SUBMITTED.name(), pending.receiptId(),
                            backoffMillis(intent.pollNo()), intent.batchId());
                }

                case IrsTransmissionClient.StatusResult.Resolved resolved ->
                        applyAcks(intent, resolved);
            }
            return null;
        });
    }

    /**
     * Applies per-filing acknowledgments.
     *
     * <p>Idempotent by the {@code ack is null} guard, so re-applying after a crash is a
     * no-op rather than a double-write.
     */
    private void applyAcks(PollIntent intent, IrsTransmissionClient.StatusResult.Resolved resolved) {
        Map<String, IrsTransmissionClient.FilingAck> byReference = new HashMap<>();
        for (IrsTransmissionClient.FilingAck ack : resolved.acks()) {
            byReference.put(ack.clientReference(), ack);
        }

        List<Map<String, Object>> members = jdbc.queryForList("""
                select filing_id, ack from app.filing_batch_member where batch_id = ?
                """, intent.batchId());

        Set<String> memberIds = new HashSet<>();
        for (Map<String, Object> member : members) {
            memberIds.add(member.get("filing_id").toString());
        }

        // Structural check before applying anything. If the endpoint returned acks for
        // filings we did not send, or omitted ones we did, that is a discrepancy a human
        // must see -- we apply the intersection and flag the rest rather than guessing.
        if (!byReference.keySet().equals(memberIds)) {
            Set<String> unexpected = new HashSet<>(byReference.keySet());
            unexpected.removeAll(memberIds);
            Set<String> missing = new HashSet<>(memberIds);
            missing.removeAll(byReference.keySet());

            attention.raise(AttentionType.ACK_RECONCILIATION_MISMATCH,
                    "BATCH", intent.batchId().toString(), intent.clientId(),
                    Map.of("unexpectedRefs", unexpected, "missingRefs", missing));
        }

        crashHooks.reached(CrashHooks.CrashPoint.DURING_ACK_APPLY);

        int applied = 0;
        for (Map<String, Object> member : members) {
            if (member.get("ack") != null) {
                continue;   // already applied; re-polling must not double-write
            }
            String filingId = member.get("filing_id").toString();
            IrsTransmissionClient.FilingAck ack = byReference.get(filingId);
            if (ack == null) {
                continue;   // flagged above; leave it unacked so polling continues
            }

            jdbc.update("""
                    update app.filing_batch_member
                       set ack = ?, ack_code = ?, ack_detail = ?, irs_record_id = ?,
                           acked_at = clock_timestamp()
                     where batch_id = ? and filing_id = ?::uuid
                    """, ack.accepted() ? "ACCEPTED" : "REJECTED",
                    ack.reasonCode(), ack.reasonText(), ack.irsRecordId(),
                    intent.batchId(), filingId);

            jdbc.update("""
                    update app.filing
                       set state = ?, irs_record_id = ?, reject_code = ?, reject_detail = ?,
                           state_changed_at = clock_timestamp()
                     where id = ?::uuid and state = ?
                    """, ack.accepted() ? FilingState.ACCEPTED.name() : FilingState.REJECTED.name(),
                    ack.irsRecordId(), ack.reasonCode(), ack.reasonText(),
                    filingId, FilingState.SUBMITTED_UNACKNOWLEDGED.name());

            if (!ack.accepted()) {
                attention.raise(AttentionType.FILING_REJECTED,
                        "FILING", filingId, intent.clientId(),
                        Map.of("reasonCode", String.valueOf(ack.reasonCode()),
                               "reasonText", String.valueOf(ack.reasonText())));
            }
            applied++;
        }

        Long stillUnacked = jdbc.queryForObject("""
                select count(*) from app.filing_batch_member where batch_id = ? and ack is null
                """, Long.class, intent.batchId());

        if (stillUnacked != null && stillUnacked == 0) {
            jdbc.update("""
                    update app.filing_batch
                       set state = ?, acknowledged_at = clock_timestamp(), needs_reconcile = false,
                           lease_owner = null, lease_expires_at = null
                     where id = ?
                    """, BatchState.ACKNOWLEDGED.name(), intent.batchId());

            // Resolved in the SAME transaction that settles the batch, so the page can never
            // show a stale warning about something already finished.
            attention.resolveAll("BATCH", intent.batchId().toString(), "system",
                    AttentionType.SUBMISSION_UNACKNOWLEDGED_TOO_LONG,
                    AttentionType.SUBMISSION_INDETERMINATE_TOO_LONG,
                    AttentionType.TRANSMISSION_RETRIES_EXHAUSTED);
        } else {
            jdbc.update("""
                    update app.filing_batch
                       set lease_owner = null, lease_expires_at = null,
                           next_action_at = clock_timestamp() + (? || ' milliseconds')::interval
                     where id = ?
                    """, backoffMillis(intent.pollNo()), intent.batchId());
        }

        log.debug("batch {} acks applied={} still_unacked={}", intent.batchId(), applied, stillUnacked);
    }

    private void reschedule(UUID batchId, int pollNo, String errorClass) {
        inTransaction("transmission:poll-reschedule", () -> {
            jdbc.update("""
                    update app.filing_batch
                       set lease_owner = null, lease_expires_at = null,
                           next_action_at = clock_timestamp() + (? || ' milliseconds')::interval,
                           last_error_class = ?
                     where id = ?
                    """, backoffMillis(pollNo), errorClass, batchId);
            return null;
        });
    }

    /**
     * Exponential backoff with full jitter, capped at {@code irs.poll.max-interval}.
     *
     * <p>The <em>interval</em> is capped; the <em>count</em> is not. The brief says
     * acknowledgment takes "minutes to hours, occasionally never" and that the design should
     * not care which &mdash; so there is no maximum poll count and no abandonment. A batch
     * polls every fifteen minutes indefinitely, which is what makes "occasionally never" a
     * non-event rather than a special case.
     */
    private long backoffMillis(int pollNo) {
        double base = initialPollDelay.toMillis() * Math.pow(backoffMultiplier, pollNo - 1);
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
