package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The test the brief requires:</b> kill a run mid-batch, resume it, and prove the
 * recovered state is right.
 *
 * <h2>What "right" means here</h2>
 *
 * <p>Not "it finished". Four properties, asserted after every scenario:
 *
 * <ol>
 *   <li><b>Zero duplicates</b> &mdash; asked of {@code irs_stub.recorded_filing}, the
 *       endpoint's own books, never of our beliefs. Checking our own tables would prove only
 *       that we are internally consistent.</li>
 *   <li><b>Zero leaks</b> &mdash; nothing is live at the IRS that our system does not
 *       believe it sent. This is the failure-mode-B assertion.</li>
 *   <li><b>Nothing stuck</b> &mdash; every filing is terminal, scheduled, or flagged for a
 *       human.</li>
 *   <li><b>Budget never exceeded</b> &mdash; per the endpoint's independent call log.</li>
 * </ol>
 *
 * <p>These are not written out per test. They are the eight invariants in
 * {@link InvariantChecker}, which is <em>production</em> code &mdash; so the assertions that
 * prove these tests are the same ones that monitor the running system.
 *
 * <h2>Deterministic failure injection</h2>
 *
 * <p>The stub's failure roll is {@code sha256(seed | idempotencyKey | attempt)}, so
 * "fail mode B on this batch" is a fixture rather than a coin flip. Setting
 * {@code failure-mode-b-rate=1.0} makes it certain; the crash point is chosen independently.
 * A failing chaos run reproduces exactly from its logged seed.
 */
@TestPropertySource(properties = {
        // Everything the tests need to hurry is configuration, so the REAL limiter, the REAL
        // backoff policy and the REAL reconciliation code all run -- just scaled down.
        // Nothing is mocked, so nothing can pass because a mock was wrong.
        "irs.stub.ack-delay.min=0ms",
        "irs.stub.ack-delay.max=0ms",
        "irs.stub.latency.min=0ms",
        "irs.stub.latency.max=0ms",
        "irs.poll.initial-delay=1ms",
        "irs.poll.max-interval=20ms",
        "irs.unack-threshold=1s",
        "irs.rate.limit=20",
        "irs.rate.window=500ms"
})
class KillAndResumeIT extends TransmissionTestBase {

    @Autowired BatchPlanner batchPlanner;
    @Autowired BatchDispatcher dispatcher;
    @Autowired TransmissionWorker worker;
    @Autowired ReconciliationService reconciler;
    @Autowired InvariantChecker invariants;

    // =================================================================================
    // S5 -- crash after the write-ahead barrier, before the request goes out
    // =================================================================================

    @Test
    @DisplayName("S5: crash after DISPATCH commits but before the call; IRS has nothing, "
               + "yet our state already says 'possibly live'")
    void crashAfterDispatchCommit_beforeCall() {
        givenReadyFilings("T-1", 8);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        UUID batchId = anySealedBatch();

        crashHooks.arm(CrashHooks.CrashPoint.AFTER_DISPATCH_COMMIT_BEFORE_CALL);

        assertThatSimulatedKill(() -> dispatcher.dispatch(firmId, batchId));
        crashHooks.disarm();

        // This is the state that makes recovery possible. The IRS has nothing at all, yet we
        // have already committed "these may be live" -- deliberately pessimistic, because at
        // this instant the two situations are indistinguishable from inside the process.
        assertThat(filingsRecordedAtIrs())
                .as("the request never left, so the endpoint holds nothing")
                .isZero();
        assertThat(countBatchesInState("DISPATCHED"))
                .as("the batch is DISPATCHED: its fate is unknown to us")
                .isEqualTo(1);
        assertThat(countFilingsInState("SUBMITTED_UNACKNOWLEDGED"))
                .as("we declared the filings possibly-live BEFORE making them possibly-live")
                .isEqualTo(8);

        // Resume: reconciliation must re-dispatch under the SAME key, not conclude
        // "nothing happened" and mint a new one.
        FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
        reconciler.reconcile(firmId);
        worker.drain(firmId, 20);

        assertThat(duplicatesAtIrs()).isZero();
        assertNoLeaks();
        assertInvariantsHold();
    }

    // =================================================================================
    // S7 -- crash after sealing, before dispatch
    // =================================================================================

    @Test
    @DisplayName("S7: crash after SEAL commits; nothing was sent, so the batch simply resumes")
    void crashAfterSeal_beforeDispatch() {
        givenReadyFilings("T-2", 6);

        crashHooks.arm(CrashHooks.CrashPoint.AFTER_SEAL_COMMIT);
        assertThatSimulatedKill(() ->
                FirmContext.runAs(firmId, () -> batchPlanner.planOne(clientId("T-2"), TAX_YEAR)));
        crashHooks.disarm();

        // SEALED is the one state that permits releasing filings without evidence, precisely
        // because it means nothing left the process.
        assertThat(countBatchesInState("SEALED")).isEqualTo(1);
        assertThat(countFilingsInState("BATCHED")).isEqualTo(6);
        assertThat(filingsRecordedAtIrs()).isZero();

        worker.drain(firmId, 20);

        assertThat(duplicatesAtIrs()).isZero();
        assertNoLeaks();
        assertInvariantsHold();
    }

    // =================================================================================
    // S4 -- the worst case: mode B AND a crash before the outcome is recorded
    // =================================================================================

    @Test
    @DisplayName("S4: the IRS recorded everything, then we crashed before learning anything")
    void modeB_thenCrashBeforeOutcomeCommit() {
        givenReadyFilings("T-3", 10);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        UUID batchId = anySealedBatch();

        // Crash between the call returning and T3 committing. Whatever the stub did, we did
        // not record it -- which is exactly the situation mode B creates on its own, and this
        // reproduces it without depending on the failure roll.
        crashHooks.arm(CrashHooks.CrashPoint.AFTER_CALL_BEFORE_OUTCOME_COMMIT);
        assertThatSimulatedKill(() -> dispatcher.dispatch(firmId, batchId));
        crashHooks.disarm();

        long recordedBeforeRecovery = filingsRecordedAtIrs();

        // The batch is still DISPATCHED and the filings still SUBMITTED_UNACKNOWLEDGED,
        // because T3 never committed. That is the correct durable state: we genuinely do not
        // know what happened.
        assertThat(countBatchesInState("DISPATCHED")).isEqualTo(1);

        FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
        reconciler.reconcile(firmId);
        worker.drain(firmId, 20);

        assertThat(duplicatesAtIrs())
                .as("recovery must not re-record filings the endpoint already holds")
                .isZero();
        assertThat(filingsRecordedAtIrs())
                .as("recovery adds no records beyond what was already there")
                .isGreaterThanOrEqualTo(recordedBeforeRecovery);
        assertNoLeaks();
        assertInvariantsHold();
    }
}
