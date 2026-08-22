package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint does <b>not</b> deduplicate, and mode B fires on every call.
 *
 * <h2>Why this scenario is the one that decides whether the design is real</h2>
 *
 * <p>Everything else in Part 3 is comfortable if the server honours idempotency keys: resend
 * under the same key, the server recognises it, nothing duplicates. That is a design leaning on
 * a property of somebody else's system.
 *
 * <p>Here {@code idempotent-replay=false} takes that property away. A resubmission under a key
 * the server has already recorded is <b>recorded a second time</b> &mdash; a real duplicate
 * filing, the exact outcome the brief forbids. Combined with
 * {@code failure-mode-b-rate=1.0}, every single call records the filings and then reports
 * failure, so the naive recovery ("we got an error, try again") duplicates everything.
 *
 * <p>What must save us is {@code reconcile-strategy=STATUS_FIRST}: <b>ask before sending.</b>
 * A status call is proof-carrying and costs one token; a blind resend is a guess that costs a
 * duplicate. The whole point of modelling errors by epistemic class rather than by HTTP status
 * is to make that distinction available at the moment it matters.
 *
 * <p>This is also the test that stops {@code irs.stub.idempotent-replay} being a knob nobody
 * ever turns. A configuration switch that is never exercised documents an intention, not a
 * capability.
 */
@TestPropertySource(properties = {
        // The server keeps no idempotency store. Resending is genuinely dangerous.
        "irs.stub.idempotent-replay=false",
        // Ask, never assume.
        "irs.reconcile-strategy=STATUS_FIRST",
        // Every call records and then lies.
        "irs.stub.failure-mode-a-rate=0.0",
        "irs.stub.failure-mode-b-rate=1.0",
        "irs.stub.ack-delay.min=0ms",
        "irs.stub.ack-delay.max=0ms",
        "irs.stub.latency.min=0ms",
        "irs.stub.latency.max=0ms",
        "irs.poll.initial-delay=1ms",
        "irs.poll.max-interval=20ms",
        "irs.rate.window=500ms"
})
class NonDedupingEndpointIT extends TransmissionTestBase {

    @Autowired BatchPlanner batchPlanner;
    @Autowired TransmissionWorker worker;
    @Autowired ReconciliationService reconciler;
    @Autowired InvariantChecker invariants;

    @Test
    @DisplayName("a server that does not deduplicate, lying on every call: still zero duplicates")
    void statusFirstSurvivesAnEndpointThatWouldHappilyRecordTheSameFilingTwice() {
        givenReadyFilings("T-1", 12);
        givenReadyFilings("T-2", 9);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));

        worker.drain(firmId, 12);

        long recordedAfterFirstPass = filingsRecordedAtIrs();
        assertThat(recordedAfterFirstPass)
                .as("mode B: the endpoint holds these filings and told us the call failed")
                .isGreaterThan(0);

        // The state that matters. We committed "possibly live" BEFORE the call, so the filings
        // the endpoint is holding are ones we already know we may have sent -- even though
        // every response we received was an error.
        assertThat(leakedFilings())
                .as("nothing is live at the IRS that we believe we never sent")
                .isZero();

        // Recovery, repeatedly. Each round would duplicate every filing if it resent blindly,
        // because this endpoint has no idempotency store to catch it.
        for (int round = 0; round < 4; round++) {
            FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
            reconciler.reconcile(firmId);
            worker.drain(firmId, 12);

            assertThat(duplicatesAtIrs())
                    .as("round %d: a duplicate filing at the IRS is the one unrecoverable "
                      + "outcome, and no amount of recovery may produce one", round)
                    .isZero();
        }

        assertThat(filingsRecordedAtIrs())
                .as("four rounds of recovery against a non-deduplicating endpoint added nothing")
                .isEqualTo(recordedAfterFirstPass);

        assertThat(invariants.check(firmId).failures())
                .as("and the invariant suite agrees, judged against the endpoint's own books")
                .isEmpty();
    }

    @Test
    @DisplayName("reconciliation asks before it sends, so recovery costs status calls not resends")
    void recoverySpendsItsBudgetOnEvidenceRatherThanOnGuesses() {
        givenReadyFilings("T-3", 8);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        worker.drain(firmId, 6);

        long submitCallsBefore = callsOfType("SUBMIT");
        long recordedBefore = filingsRecordedAtIrs();

        FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
        reconciler.reconcile(firmId);

        long statusCallsAdded = callsOfType("STATUS") - 0;

        // The behavioural difference between STATUS_FIRST and REDISPATCH_SAME_KEY, made
        // observable: reconciliation resolved these batches without issuing a single new
        // submission. Against a deduplicating endpoint a resend is merely wasteful; against
        // this one it is a duplicate filing.
        assertThat(statusCallsAdded)
                .as("STATUS_FIRST resolves by asking")
                .isGreaterThan(0);
        assertThat(callsOfType("SUBMIT"))
                .as("and without sending anything new")
                .isEqualTo(submitCallsBefore);
        assertThat(filingsRecordedAtIrs())
                .as("so the endpoint's books are unchanged by the recovery itself")
                .isEqualTo(recordedBefore);
    }

    /**
     * Counts calls of one kind from the stub's own log.
     *
     * <p>The stub's log, not our {@code transmission_attempt} table: our own accounting would
     * only prove our arithmetic is self-consistent, which is the same reason the duplicate
     * check is asked of {@code irs_stub.recorded_filing}.
     */
    private long callsOfType(String callType) {
        Long count = inSystemTransaction(() -> jdbc.queryForObject(
                "select count(*) from irs_stub.call_log where call_type = ? and firm_id = ?",
                Long.class, callType, firmId));
        return count == null ? 0 : count;
    }
}
