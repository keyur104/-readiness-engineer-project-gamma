package com.soraban.readiness.seed;

import com.soraban.readiness.ledger.EntryType;
import com.soraban.readiness.ledger.ExpenseClass;
import com.soraban.readiness.ledger.PaymentMethod;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the vendors and payments for each planted determination scenario, together with
 * the outcome the engine must produce for them.
 *
 * <h2>Why the expectation is computed here and not by the test</h2>
 *
 * <p>Each method below states the answer explicitly &mdash; {@code reportableCents = 25_000}
 * &mdash; rather than deriving it from the rows it just wrote. Deriving it would mean the
 * fixture and the system under test could share a misconception: if both summed gross
 * instead of net, both would agree, and the test would pass while the system filed a form
 * for $800 that was never owed.
 *
 * <p>Writing the expected number by hand, from the brief's own wording, makes the fixture an
 * independent statement of what is correct. That is the whole point of it.
 */
final class FixturePlanter {

    /**
     * @param vendors  the vendor(s) this case creates; more than one where the case is
     *                 specifically about identity <em>not</em> merging
     * @param rows     the payment rows to write
     * @param expected what determination must conclude
     */
    record Planted(
            List<GeneratedVendor> vendors,
            List<PaymentRow> rows,
            FixtureManifest.Expectation expected
    ) {
    }

    private FixturePlanter() {
    }

    /**
     * @param fixtureCase  which scenario to plant
     * @param clientRef    the client these payments belong to
     * @param sourceSystem the export file these rows go to
     * @param taxYear      the filing year
     * @param tinSeq       a unique 7-digit suffix; the TIN becomes {@code 99}+suffix, so a
     *                     human can find every planted vendor with {@code tin LIKE '99%'}
     * @param idSeq        seed for generating source transaction ids
     */
    static Planted plant(FixtureCase fixtureCase, String clientRef, String sourceSystem,
                         int taxYear, int tinSeq, int idSeq) {
        return switch (fixtureCase) {
            case THREE_SPELLINGS_ONE_TIN -> threeSpellings(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case DECEMBER_REVERSAL -> decemberReversal(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case EXACTLY_SIX_HUNDRED -> exactlySixHundred(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case JUST_UNDER_THRESHOLD -> justUnderThreshold(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case NO_TIN -> noTin(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case CARD_MIX_BELOW_THRESHOLD -> cardMixBelow(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case BACKUP_WITHHOLDING -> backupWithholding(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case TIN_BACKFILL_PROMOTION -> tinBackfill(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case AMBIGUOUS_NAME_TWO_TINS -> ambiguousName(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
            case CARD_MIX_ABOVE_THRESHOLD -> cardMixAbove(clientRef, sourceSystem, taxYear, tinSeq, idSeq);
        };
    }

    // =================================================================================
    // Case 1 -- three spellings, one TIN
    // =================================================================================

    /**
     * $300.00 + $250.00 + $275.00 = $825.00, recorded under three spellings of one name.
     *
     * <p>The assertion that matters is not the total &mdash; it is
     * {@code count(distinct vendor_key) = 1}. Three name strings, one vendor. An
     * implementation that grouped by name would produce three vendors of $300, $250 and
     * $275, file nothing, and still report the same $825 if you asked it for a client total.
     */
    private static Planted threeSpellings(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        List<String> spellings = List.of("Acme Plumbing", "ACME PLUMBING LLC", "Acme Plumbing, L.L.C.");

        GeneratedVendor vendor = new GeneratedVendor(
                "fx1-" + tinSeq, spellings, tin, "EIN_DASH", "EIN",
                false, false, FixtureCase.THREE_SPELLINGS_ONE_TIN);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, spellings.get(0), vendor,
                    LocalDate.of(year, 3, 4), 30_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Invoice 4471"),
                row(source, txnId(source, idSeq + 1), clientRef, spellings.get(1), vendor,
                    LocalDate.of(year, 7, 19), 25_000, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Invoice 4980"),
                row(source, txnId(source, idSeq + 2), clientRef, spellings.get(2), vendor,
                    LocalDate.of(year, 11, 2), 27_500, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Invoice 5522")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 3, 82_500, 0, 0, 82_500, 0, true, "THRESHOLD_MET", List.of()));
    }

    // =================================================================================
    // Case 2 -- December reversal: gross $800, net $250
    // =================================================================================

    /**
     * The case that catches a gross-based implementation.
     *
     * <p>$250.00 in April, $550.00 on 18 December, and the December payment reversed on the
     * 29th. Gross for the year is $800.00; net is $250.00, which is below threshold, so
     * <b>no form is required</b>.
     *
     * <p>The test asserts that no filing is created &mdash; not merely that the total comes
     * out right. A system that sums gross would file a form for $800 that is not owed, and
     * a test checking only "is the total $250" would not notice, because the total can be
     * right while the decision is wrong.
     */
    private static Planted decemberReversal(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        String name = "Summit Carpentry";
        String reversedTxn = txnId(source, idSeq + 1);

        GeneratedVendor vendor = new GeneratedVendor(
                "fx2-" + tinSeq, List.of(name), tin, "EIN_DASH", "EIN",
                false, false, FixtureCase.DECEMBER_REVERSAL);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendor,
                    LocalDate.of(year, 4, 3), 25_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Job 118 - progress billing"),
                row(source, reversedTxn, clientRef, name, vendor,
                    LocalDate.of(year, 12, 18), 55_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Job 118 - final"),
                row(source, txnId(source, idSeq + 2), clientRef, name, vendor,
                    LocalDate.of(year, 12, 29), -55_000, PaymentMethod.CHECK, EntryType.REVERSAL,
                    reversedTxn, 0, "Returned - work not completed")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 3, 80_000, 0, 55_000, 25_000, 0, false, "BELOW_THRESHOLD", List.of()));
    }

    // =================================================================================
    // Case 3 -- exactly $600.00, and its paired negative
    // =================================================================================

    /**
     * $199.99 + $200.01 + $200.00 = exactly $600.00.
     *
     * <p>The components are deliberately not round. Under {@code double} they sum to
     * 600.0000000000001, so a floating-point implementation would pass a
     * {@code >= 600.0} check <em>for the wrong reason</em> while failing a {@code <= 600}
     * check elsewhere in the same system. In integer cents the sum is exactly 60000 and
     * {@code >= 60000} is unambiguous.
     */
    private static Planted exactlySixHundred(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        String name = "Fairview Drywall";

        GeneratedVendor vendor = new GeneratedVendor(
                "fx3-" + tinSeq, List.of(name), tin, "EIN_DASH", "EIN",
                false, false, FixtureCase.EXACTLY_SIX_HUNDRED);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendor,
                    LocalDate.of(year, 2, 20), 19_999, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Invoice 2201"),
                row(source, txnId(source, idSeq + 1), clientRef, name, vendor,
                    LocalDate.of(year, 6, 11), 20_001, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Invoice 2410"),
                row(source, txnId(source, idSeq + 2), clientRef, name, vendor,
                    LocalDate.of(year, 10, 8), 20_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Invoice 2788")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 3, 60_000, 0, 0, 60_000, 0, true, "THRESHOLD_MET", List.of()));
    }

    /**
     * $599.99 &mdash; one cent below. Must <b>not</b> require a form.
     *
     * <p>Exists so the boundary is pinned from both sides. A system that files for
     * everything passes the $600.00 case; a system that files for nothing passes this one.
     * Only the pair catches {@code >} where {@code >=} was needed, or the reverse.
     */
    private static Planted justUnderThreshold(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        String name = "Lakeshore Glazing";

        GeneratedVendor vendor = new GeneratedVendor(
                "fx3b-" + tinSeq, List.of(name), tin, "EIN_DASH", "EIN",
                false, false, FixtureCase.JUST_UNDER_THRESHOLD);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendor,
                    LocalDate.of(year, 4, 17), 29_999, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Invoice 3310"),
                row(source, txnId(source, idSeq + 1), clientRef, name, vendor,
                    LocalDate.of(year, 9, 5), 30_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Invoice 3612")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 2, 59_999, 0, 0, 59_999, 0, false, "BELOW_THRESHOLD", List.of()));
    }

    // =================================================================================
    // Case 4 -- no TIN
    // =================================================================================

    /**
     * $450.00 + $400.00 = $850.00, with no TIN anywhere.
     *
     * <p>The obligation survives entirely: a form is required, and the missing TIN is an
     * exception for a human (a W-9 needs collecting) rather than a reason to drop the
     * vendor. The assertion that matters is that the vendor <b>exists</b> in the
     * determination output with {@code form_required = true} &mdash; absence is exactly the
     * failure mode the brief guards against.
     */
    private static Planted noTin(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String name = "Riverbend Landscaping";

        GeneratedVendor vendor = new GeneratedVendor(
                "fx4-" + tinSeq, List.of(name), null, "NONE", "",
                false, false, FixtureCase.NO_TIN);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendor,
                    LocalDate.of(year, 5, 12), 45_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Spring cleanup"),
                row(source, txnId(source, idSeq + 1), clientRef, name, vendor,
                    LocalDate.of(year, 9, 30), 40_000, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Fall maintenance")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 2, 85_000, 0, 0, 85_000, 0, true, "THRESHOLD_MET", List.of("MISSING_TIN")));
    }

    // =================================================================================
    // Case 5 -- card mix, below threshold on the non-card portion
    // =================================================================================

    /**
     * $2,400.00 paid, of which $1,900.00 by credit card. Non-card portion is $500.00, so
     * <b>no form</b>.
     *
     * <p>The card portion is reported by the processor on Form 1099-K, so it counts toward
     * neither the threshold nor Box 1.
     */
    private static Planted cardMixBelow(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        String name = "Copper Creek Marketing";

        GeneratedVendor vendor = new GeneratedVendor(
                "fx5-" + tinSeq, List.of(name), tin, "EIN_DASH", "EIN",
                false, false, FixtureCase.CARD_MIX_BELOW_THRESHOLD);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendor,
                    LocalDate.of(year, 2, 11), 120_000, PaymentMethod.CREDIT_CARD, EntryType.PAYMENT,
                    null, 0, "Campaign retainer"),
                row(source, txnId(source, idSeq + 1), clientRef, name, vendor,
                    LocalDate.of(year, 5, 6), 70_000, PaymentMethod.CREDIT_CARD, EntryType.PAYMENT,
                    null, 0, "Ad spend Q2"),
                row(source, txnId(source, idSeq + 2), clientRef, name, vendor,
                    LocalDate.of(year, 7, 22), 30_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Design work"),
                row(source, txnId(source, idSeq + 3), clientRef, name, vendor,
                    LocalDate.of(year, 9, 14), 20_000, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Copywriting")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 4, 240_000, 190_000, 0, 50_000, 0, false, "BELOW_THRESHOLD", List.of()));
    }

    /**
     * Case 5b &mdash; $2,400.00 total with $1,750.00 card and $650.00 non-card.
     *
     * <p>This is the fixture that pins down the <em>reported amount</em>, which the brief
     * does not spell out. A form <b>is</b> required, and Box 1 is <b>$650.00, not
     * $2,400.00</b>: the card portion is excluded from the reported amount as well as from
     * the threshold test.
     *
     * <p>The reasoning is that the processor already reports the card portion on Form
     * 1099-K. Putting it in Box 1 as well would report the same income twice under the
     * vendor's TIN and trigger an under-reporting notice <em>against the contractor</em> --
     * which is the error a CPA firm actually gets a phone call about. Threshold basis and
     * reported basis are therefore the same number.
     */
    private static Planted cardMixAbove(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        String name = "Ironwood Staffing";

        GeneratedVendor vendor = new GeneratedVendor(
                "fx5b-" + tinSeq, List.of(name), tin, "EIN_DASH", "EIN",
                false, false, FixtureCase.CARD_MIX_ABOVE_THRESHOLD);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendor,
                    LocalDate.of(year, 1, 30), 100_000, PaymentMethod.CREDIT_CARD, EntryType.PAYMENT,
                    null, 0, "Temp placement Q1"),
                row(source, txnId(source, idSeq + 1), clientRef, name, vendor,
                    LocalDate.of(year, 6, 18), 75_000, PaymentMethod.TPSO, EntryType.PAYMENT,
                    null, 0, "Temp placement Q2"),
                row(source, txnId(source, idSeq + 2), clientRef, name, vendor,
                    LocalDate.of(year, 8, 9), 40_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Direct hire fee"),
                row(source, txnId(source, idSeq + 3), clientRef, name, vendor,
                    LocalDate.of(year, 11, 21), 25_000, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Contract extension")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 4, 240_000, 175_000, 0, 65_000, 0, true, "THRESHOLD_MET", List.of()));
    }

    // =================================================================================
    // Case 6 -- backup withholding
    // =================================================================================

    /**
     * $400.00 paid with $96.00 of backup withholding taken (24%, the statutory rate).
     *
     * <p>Below threshold, but withholding forces a form regardless of amount. Box 1 is
     * $400.00 and Box 4 is $96.00.
     *
     * <p>The documented assumption: the CSV {@code amount} is <b>gross of</b> withholding,
     * so the vendor was credited $400.00 of income and received $304.00 in cash. The
     * opposite reading would change Box 1, and a reviewer will check which way this went
     * and whether the ambiguity was noticed at all.
     */
    private static Planted backupWithholding(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        String name = "Granite Welding";

        GeneratedVendor vendor = new GeneratedVendor(
                "fx6-" + tinSeq, List.of(name), tin, "EIN_DASH", "EIN",
                true, false, FixtureCase.BACKUP_WITHHOLDING);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendor,
                    LocalDate.of(year, 3, 14), 40_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 9_600, "Structural repair - BWH applied")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 1, 40_000, 0, 0, 40_000, 9_600, true, "BACKUP_WITHHOLDING", List.of()));
    }

    // =================================================================================
    // Case 4b -- TIN backfill promotion
    // =================================================================================

    /**
     * Four blank-TIN rows totalling $520.00, plus one row carrying a TIN for $180.00, all
     * under the same normalized name.
     *
     * <p>The naive reading of "vendors are identified by TIN" splits this into two vendors
     * of $520 and $180 and files <b>nothing</b> &mdash; a missed $700 obligation, and the
     * single most likely correctness bug in Part 2.
     *
     * <p>The promotion rule merges them because the name maps to exactly one distinct valid
     * TIN across the year. Every promoted row records
     * {@code identity_source = NAME_TIN_PROMOTION}, so the merge is explainable rather than
     * magic, and no exception is raised because nothing here is ambiguous.
     */
    private static Planted tinBackfill(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tin = fixtureTin(tinSeq);
        String name = "Cedar Ridge Electric";

        GeneratedVendor vendor = new GeneratedVendor(
                "fx4b-" + tinSeq, List.of(name), tin, "EIN_DASH", "EIN",
                false, false, FixtureCase.TIN_BACKFILL_PROMOTION);

        // The four blank-TIN rows carry no TIN in the file; only the last row has one.
        GeneratedVendor blank = new GeneratedVendor(
                vendor.vendorKey(), List.of(name), null, "NONE", "",
                false, false, FixtureCase.TIN_BACKFILL_PROMOTION);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, blank,
                    LocalDate.of(year, 2, 6), 13_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Panel upgrade"),
                row(source, txnId(source, idSeq + 1), clientRef, name, blank,
                    LocalDate.of(year, 4, 22), 15_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Lighting install"),
                row(source, txnId(source, idSeq + 2), clientRef, name, blank,
                    LocalDate.of(year, 7, 3), 12_000, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Outlet repair"),
                row(source, txnId(source, idSeq + 3), clientRef, name, blank,
                    LocalDate.of(year, 9, 19), 12_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Breaker replacement"),
                // The W-9 finally arrives and the bookkeeper fills the TIN in on this one.
                row(source, txnId(source, idSeq + 4), clientRef, name, vendor,
                    LocalDate.of(year, 12, 4), 18_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Annual inspection")
        );

        return new Planted(List.of(vendor), rows, new FixtureManifest.Expectation(
                1, 5, 70_000, 0, 0, 70_000, 0, true, "THRESHOLD_MET", List.of()));
    }

    // =================================================================================
    // Case 4c -- one name, two TINs
    // =================================================================================

    /**
     * The same normalized name under two <em>different</em> TINs, plus two blank-TIN rows.
     *
     * <p>The mirror of 4b, and the reason promotion has to be asymmetric. One TIN under many
     * names merges &mdash; a TIN is a strong identifier. One name under many TINs must
     * <b>not</b> &mdash; a name is weak, and two businesses genuinely called "Smith
     * Consulting" is an ordinary occurrence, not an edge case.
     *
     * <p>So the blank-TIN rows stay in their own name-keyed unit rather than being guessed
     * into either TIN, and {@code AMBIGUOUS_VENDOR_IDENTITY} is raised listing both
     * candidates for a human. Expected vendor count is <b>3</b>: the two TIN vendors and the
     * unresolved name unit.
     *
     * <p>Both directions fail toward a person; neither fails toward silent aggregation.
     */
    private static Planted ambiguousName(String clientRef, String source, int year, int tinSeq, int idSeq) {
        String tinA = fixtureTin(tinSeq);
        String tinB = fixtureTin(tinSeq + 1);
        String name = "Smith Consulting";

        GeneratedVendor vendorA = new GeneratedVendor(
                "fx4c-a-" + tinSeq, List.of(name), tinA, "EIN_DASH", "EIN",
                false, false, FixtureCase.AMBIGUOUS_NAME_TWO_TINS);
        GeneratedVendor vendorB = new GeneratedVendor(
                "fx4c-b-" + tinSeq, List.of(name), tinB, "EIN_DASH", "EIN",
                false, false, FixtureCase.AMBIGUOUS_NAME_TWO_TINS);
        GeneratedVendor blank = new GeneratedVendor(
                "fx4c-n-" + tinSeq, List.of(name), null, "NONE", "",
                false, false, FixtureCase.AMBIGUOUS_NAME_TWO_TINS);

        List<PaymentRow> rows = List.of(
                row(source, txnId(source, idSeq), clientRef, name, vendorA,
                    LocalDate.of(year, 3, 8), 45_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Strategy engagement"),
                row(source, txnId(source, idSeq + 1), clientRef, name, vendorA,
                    LocalDate.of(year, 8, 15), 35_000, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Follow-on work"),
                row(source, txnId(source, idSeq + 2), clientRef, name, vendorB,
                    LocalDate.of(year, 5, 20), 52_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Systems review"),
                row(source, txnId(source, idSeq + 3), clientRef, name, vendorB,
                    LocalDate.of(year, 10, 30), 28_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Implementation support"),
                row(source, txnId(source, idSeq + 4), clientRef, name, blank,
                    LocalDate.of(year, 6, 2), 22_000, PaymentMethod.CHECK, EntryType.PAYMENT,
                    null, 0, "Workshop facilitation"),
                row(source, txnId(source, idSeq + 5), clientRef, name, blank,
                    LocalDate.of(year, 11, 12), 18_000, PaymentMethod.ACH, EntryType.PAYMENT,
                    null, 0, "Advisory retainer")
        );

        // Expectation describes the case as a whole: three vendors, six payments. Per-vendor
        // totals are asserted separately by the focused test, which reads the spellings and
        // both TINs from this manifest entry.
        return new Planted(List.of(vendorA, vendorB, blank), rows,
                new FixtureManifest.Expectation(
                        3, 6, 200_000, 0, 0, 200_000, 0, true, "THRESHOLD_MET",
                        List.of("AMBIGUOUS_VENDOR_IDENTITY")));
    }

    // =================================================================================
    // Helpers
    // =================================================================================

    /**
     * Fixture TINs all begin {@code 99}, which no generated vendor uses.
     *
     * <p>A second affordance alongside {@code fixtures.json}: a human debugging in psql can
     * find every planted case with {@code WHERE tin_raw LIKE '99-%'} instead of
     * cross-referencing a file.
     */
    private static String fixtureTin(int seq) {
        String suffix = Integer.toString(Math.floorMod(seq, 10_000_000));
        return FixtureCase.FIXTURE_EIN_PREFIX + "0".repeat(7 - suffix.length()) + suffix;
    }

    /**
     * Source transaction ids in the shape each system actually produces.
     *
     * <p>Spreadsheets return the empty string, because they genuinely have no such column --
     * which is precisely why tier-2 synthesized identity has to exist.
     */
    private static String txnId(String sourceSystem, int seq) {
        return switch (sourceSystem) {
            case "QUICKBOOKS" -> "QB-" + seq;
            case "XERO" -> "xer-%08x".formatted(seq * 2_654_435_761L & 0xFFFFFFFFL);
            default -> "";
        };
    }

    private static PaymentRow row(String source, String txnId, String clientRef, String vendorName,
                                  GeneratedVendor vendor, LocalDate date, long amountCents,
                                  PaymentMethod method, EntryType entryType, String reverses,
                                  long withholdingCents, String memo) {
        return PaymentRow.of(
                source, txnId, clientRef, vendorName,
                vendor.tinAsWritten(), vendor.tinType(),
                date, amountCents, method, entryType, reverses,
                withholdingCents, ExpenseClass.SERVICES, memo);
    }
}
