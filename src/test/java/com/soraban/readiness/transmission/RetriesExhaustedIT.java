package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.transmission.domain.AttentionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>"We stopped retrying" is not a terminal state.</b>
 *
 * <p>The brief is explicit:
 * <blockquote>"we stopped retrying" is not a terminal state &mdash; it's something a human
 * gets shown.</blockquote>
 *
 * <p>That sentence is easy to nod at and easy to implement wrongly, because the obvious
 * design is a {@code FAILED} state or an {@code abandoned} flag. Either satisfies "a human
 * gets shown" while quietly failing the more important half: the system must <em>keep
 * working on it</em>.
 *
 * <p>So this test asserts <b>both halves at once</b>. A design that added a terminal state
 * would pass the first assertion and fail the second, which is exactly the discrimination
 * worth having.
 */
@TestPropertySource(properties = {
        // Every submission fails before intake, forever. Retries can therefore never succeed,
        // which is the only way to reach exhaustion deterministically.
        "irs.stub.failure-mode-a-rate=1.0",
        "irs.stub.failure-mode-b-rate=0.0",
        "irs.max-attempts=2",
        "irs.stub.latency.min=0ms",
        "irs.stub.latency.max=0ms",
        "irs.poll.initial-delay=1ms",
        "irs.poll.max-interval=20ms",
        "irs.rate.window=500ms"
})
class RetriesExhaustedIT extends TransmissionTestBase {

    @Autowired BatchPlanner batchPlanner;
    @Autowired TransmissionWorker worker;
    @Autowired InvariantChecker invariants;

    @Test
    @DisplayName("exhausting retries raises an attention item AND leaves the batch scheduled")
    void retriesExhausted_isVisibleButNotTerminal() {
        givenReadyFilings("T-1", 4);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));

        for (int i = 0; i < 6; i++) {
            worker.drain(firmId, 5);
        }

        Long exhaustedItems = FirmContext.runAs(firmId, () -> inTransaction(() ->
                jdbc.queryForObject("""
                        select count(*) from app.attention_item
                         where type = ? and resolved_at is null
                        """, Long.class, AttentionType.TRANSMISSION_RETRIES_EXHAUSTED.name())));

        Long stillScheduled = FirmContext.runAs(firmId, () -> inTransaction(() ->
                jdbc.queryForObject("""
                        select count(*) from app.filing_batch
                         where state in ('SEALED', 'DISPATCHED', 'SUBMITTED')
                           and next_action_at is not null
                        """, Long.class)));

        Long terminalBatches = FirmContext.runAs(firmId, () -> inTransaction(() ->
                jdbc.queryForObject("""
                        select count(*) from app.filing_batch where state in ('ACKNOWLEDGED', 'VOID')
                        """, Long.class)));

        assertThat(exhaustedItems)
                .as("a human is told that retrying has stopped making progress")
                .isGreaterThan(0);
        assertThat(stillScheduled)
                .as("and the batch remains scheduled -- exhaustion is not abandonment")
                .isGreaterThan(0);
        assertThat(terminalBatches)
                .as("nothing was quietly moved to a terminal state to make the problem go away")
                .isZero();

        // I4 in particular: every filing is terminal, scheduled, or flagged. Exhausted
        // retries satisfy it via the second and third disjuncts simultaneously.
        assertThat(invariants.check(firmId).failures()).isEmpty();
    }
}
