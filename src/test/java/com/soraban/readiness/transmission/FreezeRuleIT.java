package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sealing freezes content &mdash; and an amendment is raised only when something was amended.
 *
 * <h2>The rule</h2>
 *
 * <p>Once a filing is batched or in flight, its {@code content_hash} is baked into an
 * idempotency key the endpoint may already have seen. Re-determination therefore must not
 * rewrite it: we would ship one figure and record another, and the recomputed key would stop
 * matching the one that went out.
 *
 * <p>What re-determination <em>may</em> do is record that the data moved underneath, so a person
 * can decide whether a correction is owed once the original settles.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The check originally fired on <b>state alone</b> — "is this filing in flight?" — and raised
 * an amendment whether or not anything had actually been amended. Re-running {@code file} is the
 * normal operating mode: drain twenty calls, wait for the rate window, drain again. So the second
 * run flagged every in-flight filing, and a six-round run against the full corpus produced
 * <b>9,199 attention items, none of them real</b>.
 *
 * <p>That was not merely noisy. It made all 250 clients {@code NEEDS_ATTENTION}, which is exactly
 * the lie of omission the morning-after page's priority ordering exists to prevent: a flag that
 * fires on everything tells a reader nothing, and the exception list becomes the thing it was
 * built to avoid.
 *
 * <p>The two tests below are the two halves of the rule, and the first one is the regression.
 */
@TestPropertySource(properties = {
        "irs.stub.failure-mode-a-rate=0.0",
        "irs.stub.failure-mode-b-rate=0.0",
        "irs.stub.ack-delay.min=0ms",
        "irs.stub.ack-delay.max=0ms",
        "irs.stub.latency.min=0ms",
        "irs.stub.latency.max=0ms",
        "irs.rate.window=500ms"
})
class FreezeRuleIT extends TransmissionTestBase {

    private static final long REPORTABLE_CENTS = 82_500L;

    @Autowired FilingPlanner filingPlanner;
    @Autowired BatchPlanner batchPlanner;

    /**
     * Clears determinations too.
     *
     * <p>The shared reset in {@link TransmissionTestBase} deletes filings and batches but not
     * determinations, because the transmission suites build filings directly and never touch
     * them. This suite drives the PLANNER, so it needs the determination tables empty or the
     * SCD-2 current-version index rejects the second test's insert. Runs after the superclass
     * hook, which JUnit guarantees.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearDeterminations() {
        FirmContext.runAs(firmId, () -> inTransaction(() -> {
            jdbc.execute("delete from app.determination_exception");
            jdbc.execute("delete from app.payment_determination");
            jdbc.execute("delete from app.vendor_determination");
            jdbc.execute("delete from app.determination_run");
            // Vendors too: the fixture derives a vendor natural_key from the client id, so a
            // second test reusing a client would collide on the uniqueness constraint.
            // ledger_line first, because it references vendor.
            jdbc.execute("delete from app.ledger_line");
            jdbc.execute("delete from app.vendor");
            return null;
        }));
    }

    @Test
    @DisplayName("re-planning an unchanged in-flight filing raises NOTHING")
    void replanningUnchangedDataIsNotAnAmendment() {
        long clientId = clientId("T-1");
        givenDetermination(clientId, "Acme Plumbing", REPORTABLE_CENTS);

        FilingPlanner.PlanResult first = FirmContext.runAs(firmId,
                () -> filingPlanner.planFilings(TAX_YEAR));
        assertThat(first.ready()).isEqualTo(1);
        assertThat(first.frozen()).isZero();

        // Seal it, which is what freezes the content.
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        assertThat(countFilingsInState("BATCHED")).isEqualTo(1);

        // Re-plan. Nothing about the determination has changed.
        FilingPlanner.PlanResult second = FirmContext.runAs(firmId,
                () -> filingPlanner.planFilings(TAX_YEAR));

        assertThat(second.frozen())
                .as("the filing is in flight, so it is left alone")
                .isEqualTo(1);
        assertThat(second.ready())
                .as("and it is not re-readied, because sealing froze it")
                .isZero();

        // The regression. "In flight" and "in flight AND the data moved" are different
        // things, and only the second is a person's problem.
        assertThat(amendmentItems())
                .as("nothing was amended, so nothing may be raised -- a flag that fires on "
                  + "every routine re-run makes the whole exception list worthless")
                .isZero();
    }

    @Test
    @DisplayName("a figure that actually changes under an in-flight filing IS raised")
    void genuinelyAmendedDataIsRaised() {
        long clientId = clientId("T-2");
        givenDetermination(clientId, "Summit Carpentry", REPORTABLE_CENTS);

        FirmContext.runAs(firmId, () -> filingPlanner.planFilings(TAX_YEAR));
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));
        assertThat(countFilingsInState("BATCHED")).isEqualTo(1);
        assertThat(amendmentItems()).isZero();

        // A revised export lands at 2 a.m. and moves the number. This is the event the rule
        // was written for: we have shipped $825.00 and the books now say $1,140.00.
        FirmContext.runAs(firmId, () -> inTransaction(() -> jdbc.update("""
                update app.vendor_determination
                   set reportable_cents = ?
                 where client_id = ? and tax_year = ? and valid_to = 'infinity'
                """, 114_000L, clientId, TAX_YEAR)));

        FilingPlanner.PlanResult after = FirmContext.runAs(firmId,
                () -> filingPlanner.planFilings(TAX_YEAR));

        assertThat(after.frozen())
                .as("still frozen: the sent version stands")
                .isEqualTo(1);

        assertThat(amendmentItems())
                .as("and now a person is told, because the data genuinely moved")
                .isEqualTo(1);

        // The filing itself is untouched. Rewriting it would change the content_hash, the
        // recomputed idempotency key would stop matching the one already at the endpoint,
        // and we would have shipped one number while recording another.
        long stored = FirmContext.runAs(firmId, () -> inTransaction(() -> jdbc.queryForObject(
                "select amount_cents from app.filing where client_id = ?", Long.class, clientId)));

        assertThat(stored)
                .as("the sealed figure is what was sent, and it does not move")
                .isEqualTo(REPORTABLE_CENTS);
    }

    @Test
    @DisplayName("re-planning many times raises nothing, however often the run repeats")
    void repeatedRunsStayQuiet() {
        givenDetermination(clientId("T-1"), "Ironwood Staffing", REPORTABLE_CENTS);
        givenDetermination(clientId("T-2"), "Cedar Ridge Electric", REPORTABLE_CENTS);
        givenDetermination(clientId("T-3"), "Fairview Drywall", REPORTABLE_CENTS);

        FirmContext.runAs(firmId, () -> filingPlanner.planFilings(TAX_YEAR));
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));

        // Six rounds is what a real filing run does against a 20-call budget. The bug scaled
        // with exactly this: one false item per in-flight filing, per run.
        for (int round = 0; round < 6; round++) {
            FirmContext.runAs(firmId, () -> filingPlanner.planFilings(TAX_YEAR));
        }

        assertThat(amendmentItems())
                .as("six routine re-plans must leave the exception list empty")
                .isZero();
    }

    // =================================================================================

    private long amendmentItems() {
        return FirmContext.runAs(firmId, () -> inTransaction(() -> jdbc.queryForObject("""
                select count(*) from app.attention_item
                 where type = 'AMENDED_DATA_FOR_INFLIGHT_FILING' and resolved_at is null
                """, Long.class)));
    }

    /**
     * One vendor with a real encrypted TIN, and a determination that requires a form.
     *
     * <p>The vendor row is not optional scaffolding. Preflight decrypts the TIN before a filing
     * may be marked {@code READY_TO_TRANSMIT}, so a determination without one lands
     * {@code BLOCKED} — and a blocked filing is never sealed, never frozen, and this suite
     * would be asserting against a state it never reached.
     *
     * <p>Built directly rather than by importing a corpus: this test is about the planner's
     * freeze decision, and routing through import and determination would make a failure here
     * ambiguous between three subsystems.
     */
    private void givenDetermination(long clientId, String vendorName, long reportableCents) {
        FirmContext.runAs(firmId, () -> inTransaction(() -> {
            // Nine digits, not 000-prefixed, so preflight passes on the merits.
            String digits = "1" + String.valueOf(100_000_000L + clientId).substring(1);
            com.soraban.readiness.security.Tin tin =
                    com.soraban.readiness.security.Tin.parse(digits, "EIN").tin();

            byte[] bidx = tinCrypto.blindIndex(firmId, tin);
            var encrypted = tinCrypto.encrypt(firmId, clientId, bidx, tin);

            Long vendorId = jdbc.queryForObject("""
                    insert into app.vendor
                        (firm_id, client_id, natural_key, keyed_by, display_name,
                         name_norm, name_norm_version,
                         tin_ct, tin_key_ver, tin_bidx, tin_last4, tin_status)
                    values (app.current_firm_id(), ?, ?, 'TIN', ?, ?, 1, ?, ?, ?, ?, 'PRESENT')
                    returning id
                    """, Long.class,
                    clientId, bidx, vendorName, vendorName.toLowerCase(),
                    encrypted.ciphertext(), encrypted.keyVersion(), bidx, tin.last4());

            Long runId = jdbc.queryForObject("""
                    insert into app.determination_run
                        (firm_id, tax_year, mode, ruleset_hash, name_norm_version,
                         threshold_cents, state)
                    values (app.current_firm_id(), ?, 'FULL', 'freeze-test', 1, 60000, 'COMPLETED')
                    returning id
                    """, Long.class, TAX_YEAR);

            jdbc.update("""
                    insert into app.vendor_determination
                        (firm_id, client_id, vendor_key, tax_year, run_id, vendor_id, display_name,
                         tin_bidx, tin_last4, tin_status, identity_source,
                         gross_cents, reportable_cents, withholding_cents,
                         counted_payment_count, total_payment_count,
                         form_required, requirement_reason, transmit_blocked)
                    values (app.current_firm_id(), ?, ?, ?, ?, ?, ?,
                            ?, ?, 'PRESENT', 'DIRECT_TIN',
                            ?, ?, 0, 3, 3, true, 'THRESHOLD_MET', false)
                    """,
                    clientId, "TIN:freeze-" + clientId, TAX_YEAR, runId, vendorId, vendorName,
                    bidx, tin.last4(), reportableCents, reportableCents);
            return null;
        }));
    }
}
