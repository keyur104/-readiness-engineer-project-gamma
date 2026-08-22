package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IRS never answers. Ever.
 *
 * <blockquote>Acknowledgments come back minutes to hours later &mdash; occasionally never. Your
 * design shouldn't care which.</blockquote>
 *
 * <p>"Shouldn't care which" is a strong claim, and the way most designs fail it is by counting:
 * a maximum poll count, a "give up after N attempts", a terminal {@code ABANDONED} state. Any
 * of those turns "occasionally never" into a cliff, and the filing falls off it silently.
 *
 * <p>So this system <b>caps the interval and never the count</b>. A batch polls every fifteen
 * minutes forever until a person resolves it. That is what makes never a non-event rather than
 * a special case &mdash; and it is only true if nothing anywhere decrements a counter toward
 * zero, which is what this test exists to check.
 *
 * <p>{@code ack-never-rate=1.0} makes it certain rather than occasional. The unacknowledged
 * threshold is dropped to a millisecond so the attention item that should appear "after 30
 * minutes" appears immediately; the mechanism is identical, only the clock is scaled.
 */
@TestPropertySource(properties = {
        // The endpoint accepts everything and acknowledges nothing.
        "irs.stub.ack-never-rate=1.0",
        "irs.stub.failure-mode-a-rate=0.0",
        "irs.stub.failure-mode-b-rate=0.0",
        "irs.stub.ack-delay.min=0ms",
        "irs.stub.ack-delay.max=0ms",
        "irs.stub.latency.min=0ms",
        "irs.stub.latency.max=0ms",
        "irs.poll.initial-delay=1ms",
        "irs.poll.max-interval=20ms",
        // Scaled, not mocked: the real sweep runs, just against a smaller number.
        "irs.unack-threshold=1ms",
        "irs.rate.window=500ms"
})
class NeverAcknowledgedIT extends TransmissionTestBase {

    @Autowired BatchPlanner batchPlanner;
    @Autowired TransmissionWorker worker;
    @Autowired InvariantChecker invariants;

    @Test
    @DisplayName("an acknowledgment that never arrives is surfaced to a person, "
               + "and polling continues forever")
    void neverAcknowledgedIsVisibleAndNotTerminal() {
        givenReadyFilings("T-1", 10);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));

        // Submit, then poll repeatedly. Every poll gets "still nothing".
        worker.drain(firmId, 8);
        for (int round = 0; round < 6; round++) {
            worker.drain(firmId, 8);
        }

        // 1. The filings sit in the honest state and do not move.
        //
        // SUBMITTED_UNACKNOWLEDGED means "the IRS may have this". A filing here at 23:59 on
        // 2 February is FILED ON TIME -- acknowledgment latency is the IRS's clock, not ours.
        // Nothing may quietly reclassify it as failed just because we got bored waiting.
        assertThat(countFilingsInState("SUBMITTED_UNACKNOWLEDGED"))
                .as("filings stay in the state that describes what we actually know")
                .isEqualTo(10);
        assertThat(countFilingsInState("ACCEPTED")).isZero();
        assertThat(countFilingsInState("REJECTED")).isZero();

        // 2. Nothing was invented. The endpoint gave us no outcome, so we claim none.
        assertThat(invariants.check(firmId).failures())
                .as("I3 in particular: never claim an outcome the IRS has no record of giving")
                .isEmpty();

        // 3. A person has been told.
        long attention = FirmContext.runAs(firmId, () -> inTransaction(() -> jdbc.queryForObject("""
                select count(*) from app.attention_item
                 where resolved_at is null
                   and type in ('SUBMISSION_UNACKNOWLEDGED_TOO_LONG',
                                'SUBMISSION_INDETERMINATE_TOO_LONG')
                """, Long.class)));

        assertThat(attention)
                .as("waiting silently forever is indistinguishable from being stuck; the wait "
                  + "itself has to become visible")
                .isPositive();

        // 4. And the batch is STILL SCHEDULED. This is the assertion that matters most,
        //    because it is the one a "give up after N attempts" implementation fails.
        long scheduled = FirmContext.runAs(firmId, () -> inTransaction(() -> jdbc.queryForObject("""
                select count(*) from app.filing_batch
                 where state in ('DISPATCHED', 'SUBMITTED')
                   and next_action_at is not null
                """, Long.class)));

        assertThat(scheduled)
                .as("the interval is capped, never the count -- polling continues until a "
                  + "human resolves it, which is what makes 'never' a non-event")
                .isPositive();
    }

    @Test
    @DisplayName("no filing is ever quietly stuck: every one is terminal, scheduled, or flagged")
    void everyFilingIsAccountedForEvenWhenNothingIsAcknowledged() {
        givenReadyFilings("T-2", 7);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        worker.drain(firmId, 8);
        worker.drain(firmId, 8);

        // I4 is stronger than "the counts match", because a count can match perfectly while a
        // filing sits in a state nobody will ever act on. It asserts a disjunction over every
        // filing: accepted, OR on a live batch with a next action scheduled, OR named by an
        // unresolved attention item.
        //
        // "We stopped retrying" satisfies the third disjunct -- which is precisely why the
        // brief insists it is not a terminal state.
        InvariantChecker.Report report = invariants.check(firmId);

        InvariantChecker.Invariant noLostFilings = report.invariants().stream()
                .filter(i -> "I4".equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("I4 is missing from the invariant suite"));

        assertThat(noLostFilings.holds())
                .as("there must be no state in which a filing is quietly not progressing")
                .isTrue();
    }
}
