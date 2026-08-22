package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure mode B with <b>no crash at all</b> &mdash; the brief's "most important line".
 *
 * <blockquote>5% of calls record every filing, then return an error anyway. The filings are
 * live; you never see the receipt.</blockquote>
 *
 * <p>Forced to 100% here, because a 5% behaviour is not something a test should wait around
 * for. The stub's roll is a pure function of {@code (seed, key, attempt)}, so making it
 * certain changes only the probability, never the mechanism.
 *
 * <p>Its own class rather than a method in {@code KillAndResumeIT} because
 * {@code @TestPropertySource} is class-scoped: a per-method override is not possible, and
 * faking it by mutating the stub at runtime would mean the test no longer exercised the
 * configuration path a reviewer would use.
 */
@TestPropertySource(properties = {
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
class ModeBNoCrashIT extends TransmissionTestBase {

    @Autowired BatchPlanner batchPlanner;
    @Autowired TransmissionWorker worker;
    @Autowired ReconciliationService reconciler;
    @Autowired InvariantChecker invariants;

    @Test
    @DisplayName("mode B: every filing is recorded and we are told it failed; "
               + "retrying must not duplicate a single one")
    void modeB_neverProducesDuplicates() {
        givenReadyFilings("T-1", 12);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));

        worker.drain(firmId, 10);

        long recordedAfterFirstAttempt = filingsRecordedAtIrs();

        // The whole point: the endpoint HAS these filings, and our process was told the call
        // failed. If the durable state were driven by what we were told rather than by what we
        // committed beforehand, these would now be invisible to us and would be sent again.
        assertThat(recordedAfterFirstAttempt)
                .as("the stub recorded the filings before lying about it")
                .isGreaterThan(0);
        assertThat(leakedFilings())
                .as("every filing live at the IRS is one we already marked possibly-live")
                .isZero();

        // Recovery, then several more drains -- every retry goes out under the same key.
        FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
        reconciler.reconcile(firmId);
        for (int i = 0; i < 3; i++) {
            worker.drain(firmId, 10);
        }

        assertThat(duplicatesAtIrs())
                .as("no filing may be recorded twice for the same generation, ever")
                .isZero();
        assertThat(filingsRecordedAtIrs())
                .as("repeated recovery attempts add nothing at the endpoint")
                .isEqualTo(recordedAfterFirstAttempt);

        assertThat(invariants.check(firmId).failures()).isEmpty();
    }
}
