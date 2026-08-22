package com.soraban.readiness.seed;

/**
 * Deliberately broken rows planted in the corpus, each labelled with the outcome the
 * importer must produce.
 *
 * <h2>The distinction this enum exists to make concrete</h2>
 *
 * <p>There are two completely different kinds of "bad row", and conflating them is the
 * confusion the brief is testing for:
 *
 * <ul>
 *   <li>{@link Outcome#REJECTED} &mdash; <b>structural</b>. The row cannot be represented
 *       at all: there is no date to store, no amount to sum, no client to attach it to. It
 *       is skipped, reported, and never allowed to kill the import.</li>
 *   <li>{@link Outcome#IMPORTED_WITH_EXCEPTION} &mdash; <b>semantic</b>. The row is
 *       perfectly storable; something about it needs a human later. A missing TIN is the
 *       canonical example: the payment is real, the vendor still requires a form, and a
 *       W-9 needs collecting. Rejecting it at import would silently drop a vendor who has
 *       a filing obligation, which is precisely what the brief says must never happen.</li>
 * </ul>
 *
 * <p><em>"A missing TIN is not a malformed row"</em> is the sentence this enum turns into
 * code. Each constant carries its expected reason code, and the generator records every
 * planted defect in {@code fixtures.json} &mdash; so the rejection <b>report</b> is itself
 * asserted, not just the fact that the import survived.
 */
public enum DefectClass {

    // ---------------------------------------------------------------------------------
    // Structural: rejected, reported, import continues
    // ---------------------------------------------------------------------------------

    /** {@code "31/02/2025"}, {@code "not a date"}, {@code "2025-13-45"}. */
    UNPARSEABLE_DATE(Outcome.REJECTED, "UNPARSEABLE_DATE"),

    /** {@code "N/A"}, {@code ""}, {@code "1,2,3.4"}, {@code "twelve dollars"}. */
    UNPARSEABLE_AMOUNT(Outcome.REJECTED, "UNPARSEABLE_AMOUNT"),

    /**
     * {@code "12.345"}. Rejected rather than rounded: silently discarding a fraction of a
     * cent makes the imported book disagree with the source by an amount nobody can later
     * account for.
     */
    SUB_CENT_AMOUNT(Outcome.REJECTED, "SUB_CENT_AMOUNT"),

    /** No client reference at all &mdash; the payment cannot be attributed to a payer. */
    MISSING_CLIENT_REF(Outcome.REJECTED, "MISSING_CLIENT_REF"),

    /** A client reference not present in {@code clients.csv} or in this firm. */
    UNKNOWN_CLIENT_REF(Outcome.REJECTED, "UNKNOWN_CLIENT_REF"),

    /**
     * Vendor name <b>and</b> TIN both blank. The only case where a vendor genuinely cannot
     * be identified even as an exception, so there is nothing to attach an obligation to.
     */
    UNIDENTIFIABLE_VENDOR(Outcome.REJECTED, "UNIDENTIFIABLE_VENDOR"),

    /** Wrong column count, or an unescaped embedded quote that breaks the record. */
    RAGGED_ROW(Outcome.REJECTED, "RAGGED_ROW"),

    /**
     * A currency other than USD. Rejected rather than converted &mdash; inventing an
     * exchange rate would silently change a filing amount.
     */
    UNSUPPORTED_CURRENCY(Outcome.REJECTED, "UNSUPPORTED_CURRENCY"),

    // ---------------------------------------------------------------------------------
    // Semantic: imported successfully, surfaces as a determination exception
    // ---------------------------------------------------------------------------------

    /**
     * {@code "12-34567"}, {@code "ABC-DEFGH"}. Imported, preserved for a human, but never
     * used as an identity key &mdash; we will not make an unverifiable string load-bearing
     * for who a vendor is.
     */
    MALFORMED_TIN(Outcome.IMPORTED_WITH_EXCEPTION, "MALFORMED_TIN"),

    /**
     * Blank TIN. <b>Not a defect in the row</b> at all, strictly &mdash; it is ordinary,
     * extremely common data. Listed here only so the generator can plant it deliberately
     * and the test suite can assert it is never rejected.
     */
    MISSING_TIN(Outcome.IMPORTED_WITH_EXCEPTION, "MISSING_TIN"),

    /** A payment method string outside the synonym map. Counts toward the threshold; flagged. */
    UNKNOWN_PAYMENT_METHOD(Outcome.IMPORTED_WITH_EXCEPTION, "UNKNOWN_PAYMENT_METHOD"),

    /** A 2024 or 2026 date. Imported so it can be shown as excluded rather than vanishing. */
    OUT_OF_TAX_YEAR(Outcome.IMPORTED_WITH_EXCEPTION, "EXCLUDED_OUT_OF_TAX_YEAR"),

    /** A zero-amount line. Real, harmless, and worth proving does not become a $0 filing. */
    ZERO_AMOUNT(Outcome.IMPORTED_WITH_EXCEPTION, "EXCLUDED_ZERO_AMOUNT"),

    // ---------------------------------------------------------------------------------
    // Neither: collapsed with a warning
    // ---------------------------------------------------------------------------------

    /**
     * The same {@code source_txn_id} twice in one file. QuickBooks exports genuinely do
     * this.
     *
     * <p>A <b>warning</b>, not a rejection: the data is present and correct, one copy is
     * simply redundant. It matters because {@code ON CONFLICT DO UPDATE} throws
     * <em>"command cannot affect row a second time"</em> if two source rows carry the same
     * conflict key, so staging must be deduplicated before the merge. Planting this proves
     * the importer survives a real-world export quirk that a naive upsert does not.
     */
    DUPLICATE_KEY_IN_FILE(Outcome.WARNED, "DUPLICATE_KEY_IN_FILE");

    /** What the importer must do with a row carrying this defect. */
    public enum Outcome {
        /** Skipped and reported. Never aborts the import. */
        REJECTED,
        /** Stored normally; produces a determination exception for a human. */
        IMPORTED_WITH_EXCEPTION,
        /** Collapsed during staging dedupe and counted, but not an error. */
        WARNED
    }

    private final Outcome outcome;
    private final String reasonCode;

    DefectClass(Outcome outcome, String reasonCode) {
        this.outcome = outcome;
        this.reasonCode = reasonCode;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** The code the importer or determination pass must record for this row. */
    public String reasonCode() {
        return reasonCode;
    }

    public boolean isRejection() {
        return outcome == Outcome.REJECTED;
    }
}
