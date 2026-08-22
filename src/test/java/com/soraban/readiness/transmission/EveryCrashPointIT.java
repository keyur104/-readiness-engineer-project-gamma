package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The crash points {@link KillAndResumeIT} does not cover &mdash; so that every one is covered.
 *
 * <h2>Why completeness matters here specifically</h2>
 *
 * <p>{@code CrashHooks.CrashPoint} defines eight points and production code fires all eight.
 * Three had tests. The other five were hooks that existed, were called on every run, and had
 * never once been armed &mdash; which is a strictly worse position than not having them, because
 * their presence in the enum reads as coverage.
 *
 * <p>The set is also not arbitrary. Each point is a distinct <b>epistemic position</b>: a
 * different combination of what we durably recorded and what the endpoint actually holds. A
 * crash-recovery argument is only as good as the worst uncovered combination, so "we tested the
 * interesting ones" is the same as "we tested the ones we thought of".
 *
 * <p>Every scenario ends with the same four assertions, and they are the production invariants
 * rather than test-local ones: zero duplicates judged against the endpoint's own books, zero
 * leaks, nothing stuck, budget never exceeded.
 */
@TestPropertySource(properties = {
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
class EveryCrashPointIT extends TransmissionTestBase {

    @Autowired BatchPlanner batchPlanner;
    @Autowired BatchDispatcher dispatcher;
    @Autowired AckPoller poller;
    @Autowired TransmissionWorker worker;
    @Autowired ReconciliationService reconciler;
    @Autowired InvariantChecker invariants;

    // =================================================================================
    // Before anything is durable
    // =================================================================================

    @Test
    @DisplayName("crash BEFORE the seal commits: the batch never existed, and the filings "
               + "are still free to be planned again")
    void crashBeforeSealCommit() {
        givenReadyFilings("T-1", 6);

        crashHooks.arm(CrashHooks.CrashPoint.BEFORE_SEAL_COMMIT);
        assertThatSimulatedKill(() ->
                FirmContext.runAs(firmId, () -> batchPlanner.planOne(clientId("T-1"), TAX_YEAR)));
        crashHooks.disarm();

        // The transaction rolled back, so there is no half-sealed batch to reason about. This
        // is the easy case and it is worth pinning precisely because it is easy: if a batch
        // row survived here, every later argument about SEALED meaning "nothing was sent"
        // would be built on sand.
        assertThat(countBatchesInState("SEALED")).isZero();
        assertThat(countFilingsInState("READY_TO_TRANSMIT"))
                .as("the filings were never claimed, so they remain plannable")
                .isEqualTo(6);
        assertThat(filingsRecordedAtIrs()).isZero();

        // Resume: plan again. The filing ids are derived from the business key, so replanning
        // produces the same filings rather than a fresh set with new identities.
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        worker.drain(firmId, 20);

        assertThat(duplicatesAtIrs()).isZero();
        assertNoLeaks();
        assertInvariantsHold();
    }

    @Test
    @DisplayName("crash BEFORE the dispatch intent commits: the write-ahead barrier never "
               + "fell, so the batch is still SEALED and the rate token rolled back with it")
    void crashBeforeDispatchCommit() {
        givenReadyFilings("T-2", 5);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        UUID batchId = anySealedBatch();

        long callsBefore = callsAtIrs();

        crashHooks.arm(CrashHooks.CrashPoint.BEFORE_DISPATCH_COMMIT);
        assertThatSimulatedKill(() -> dispatcher.dispatch(firmId, batchId));
        crashHooks.disarm();

        // The "before" side of the barrier. Nothing was sent and nothing was recorded as
        // possibly-sent -- the two must move together or the barrier is not a barrier.
        assertThat(countBatchesInState("SEALED"))
                .as("the batch never left SEALED")
                .isEqualTo(1);
        assertThat(countFilingsInState("SUBMITTED_UNACKNOWLEDGED"))
                .as("and no filing believes it may be live")
                .isZero();
        assertThat(filingsRecordedAtIrs()).isZero();

        // The rate token is consumed INSIDE this transaction, so it rolled back too. A token
        // spent on a call that never happened would be a slow leak of the scarcest resource
        // in the system -- 20 a minute, and no way to notice they were going missing.
        assertThat(callsAtIrs())
                .as("a rolled-back dispatch consumes no budget")
                .isEqualTo(callsBefore);

        worker.drain(firmId, 20);
        assertThat(duplicatesAtIrs()).isZero();
        assertNoLeaks();
        assertInvariantsHold();
    }

    // =================================================================================
    // The window where the endpoint's state is genuinely unknown
    // =================================================================================

    @Test
    @DisplayName("crash DURING the call: bytes may or may not have arrived, and the only "
               + "honest position is that we do not know")
    void crashDuringTheCall() {
        givenReadyFilings("T-3", 7);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        UUID batchId = anySealedBatch();

        crashHooks.arm(CrashHooks.CrashPoint.DURING_CALL);
        assertThatSimulatedKill(() -> dispatcher.dispatch(firmId, batchId));
        crashHooks.disarm();

        // This is the case the whole design is arranged around. We cannot know whether the
        // endpoint received it, so the durable state must already say "possibly live" -- and
        // it does, because the barrier committed first.
        assertThat(countBatchesInState("DISPATCHED")).isEqualTo(1);
        assertThat(countFilingsInState("SUBMITTED_UNACKNOWLEDGED")).isEqualTo(7);

        // Recovery is proof-carrying: only an answer from the endpoint moves it.
        FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
        reconciler.reconcile(firmId);
        worker.drain(firmId, 20);

        assertThat(duplicatesAtIrs())
                .as("whether or not the bytes arrived, recovery must not produce a second filing")
                .isZero();
        assertNoLeaks();
        assertInvariantsHold();
    }

    // =================================================================================
    // After the endpoint has answered
    // =================================================================================

    @Test
    @DisplayName("crash after the receipt is stored, before the first poll: polling simply "
               + "resumes, and nothing is re-sent")
    void crashAfterReceiptBeforePoll() {
        givenReadyFilings("T-1", 6);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        UUID batchId = anySealedBatch();

        // Get a real receipt first: no failures for this one.
        dispatcher.dispatch(firmId, batchId);
        assertThat(countBatchesInState("SUBMITTED"))
                .as("the batch holds a receipt")
                .isEqualTo(1);

        long recordedBefore = filingsRecordedAtIrs();

        crashHooks.arm(CrashHooks.CrashPoint.AFTER_RECEIPT_BEFORE_POLL);
        assertThatSimulatedKill(() -> poller.poll(firmId, batchId));
        crashHooks.disarm();

        // A batch with a receipt is not ambiguous. There is a specific thing to ask about, so
        // recovery is an ordinary poll rather than reconciliation -- and crucially it must not
        // re-submit, because SUBMITTED means the endpoint has definitely got it.
        assertThat(countBatchesInState("SUBMITTED")).isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            worker.drain(firmId, 20);
        }

        assertThat(filingsRecordedAtIrs())
                .as("resuming a receipted batch adds nothing at the endpoint")
                .isEqualTo(recordedBefore);
        assertThat(duplicatesAtIrs()).isZero();
        assertNoLeaks();
        assertInvariantsHold();
    }

    @Test
    @DisplayName("crash while acknowledgments are being applied: re-polling must finish the "
               + "job without double-applying any of it")
    void crashDuringAckApply() {
        givenReadyFilings("T-2", 9);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        UUID batchId = anySealedBatch();

        dispatcher.dispatch(firmId, batchId);

        // Crash partway through writing outcomes. This is the subtlest point in the set: the
        // endpoint has answered, we are mid-way through believing it, and the danger is not a
        // duplicate FILING but a corrupted local record -- a filing marked accepted twice, or
        // one whose ack is silently dropped because the batch looks handled.
        crashHooks.arm(CrashHooks.CrashPoint.DURING_ACK_APPLY);
        assertThatSimulatedKill(() -> poller.poll(firmId, batchId));
        crashHooks.disarm();

        // The apply transaction rolled back as a unit, so no filing is half-acknowledged.
        long terminal = countFilingsInState("ACCEPTED") + countFilingsInState("REJECTED");
        assertThat(terminal)
                .as("acks apply atomically: either the batch's outcomes are recorded or none are")
                .isIn(0L, 9L);

        // The crashed worker still holds the batch's LEASE. That is the lease doing its job:
        // a process that dies mid-work must not have its batch picked up by another worker
        // the same instant, because the first one might not actually be dead. The batch
        // resumes when the lease expires.
        //
        // Two minutes is a long time to sit in a test, and this duration is the one timing in
        // the whole system that is NOT configuration -- it is a hardcoded interval in the
        // claim query, so the test profile cannot scale it down the way it scales the rate
        // window and the poll backoff. That is precisely why this path had no test. Expiring
        // it explicitly is the honest stand-in for waiting.
        expireLeases();

        for (int i = 0; i < 6; i++) {
            worker.drain(firmId, 20);
        }

        // Every filing ends up with exactly one outcome, and it matches what the endpoint said.
        // I3 is the invariant that would catch an invented outcome; I1 would catch a duplicate.
        assertThat(duplicatesAtIrs()).isZero();
        assertNoLeaks();
        assertInvariantsHold();

        long stillUnacknowledged = countFilingsInState("SUBMITTED_UNACKNOWLEDGED");
        assertThat(stillUnacknowledged)
                .as("re-polling finished the interrupted apply rather than abandoning it")
                .isZero();
    }

    // =================================================================================
    // Every point, one after another
    // =================================================================================

    @Test
    @DisplayName("all eight crash points in sequence against one book: still zero duplicates")
    void everyCrashPointInTurn() {
        // Individually each point is a hypothesis about one moment. In sequence they are a
        // question about the system: does recovery from one crash leave state that survives
        // the next? A batch left mid-recovery by scenario N is the input to scenario N+1,
        // which is much closer to what a bad night actually looks like than any single kill.
        givenReadyFilings("T-1", 5);
        givenReadyFilings("T-2", 5);
        givenReadyFilings("T-3", 5);

        for (CrashHooks.CrashPoint point : CrashHooks.CrashPoint.values()) {
            crashHooks.arm(point);
            try {
                FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
                worker.drain(firmId, 6);
            } catch (CrashHooks.SimulatedKill expected) {
                // The point of the exercise.
            } finally {
                crashHooks.disarm();
            }

            // Recover before moving on, exactly as a restart would.
            FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
            reconciler.reconcile(firmId);

            assertThat(duplicatesAtIrs())
                    .as("after crashing at %s, the endpoint holds no filing twice", point)
                    .isZero();
        }

        // Drain to completion with no crashes armed, then assert the whole suite.
        for (int i = 0; i < 8; i++) {
            worker.drain(firmId, 20);
        }

        assertThat(duplicatesAtIrs()).isZero();
        assertNoLeaks();
        assertInvariantsHold();
    }

    // =================================================================================

    /**
     * Brings forward every lease, standing in for the two minutes a restart would wait.
     *
     * <p>Deliberately not a clock mock: it moves the data, not time, so the real claim query
     * with its real predicate is what decides the batch is available again.
     */
    private void expireLeases() {
        FirmContext.runAs(firmId, () -> inTransaction(() -> jdbc.update(
                "update app.filing_batch set lease_expires_at = clock_timestamp() - interval '1 second' "
                + "where lease_expires_at is not null")));
    }

    /** Calls the endpoint has logged, read from its own books rather than our accounting. */
    private long callsAtIrs() {
        Long count = inSystemTransaction(() -> jdbc.queryForObject(
                "select count(*) from irs_stub.call_log where firm_id = ?", Long.class, firmId));
        return count == null ? 0 : count;
    }
}
