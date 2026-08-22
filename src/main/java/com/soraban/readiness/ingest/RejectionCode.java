package com.soraban.readiness.ingest;

/**
 * Why a row could not be imported.
 *
 * <p>Every code here is <b>structural</b>: the row cannot be represented at all. There is
 * no date to store, no amount to sum, or no client to attach the payment to.
 *
 * <p>Deliberately absent, and worth stating because it is the confusion the brief is
 * testing for: <b>a missing TIN is not here.</b> Nor is a malformed TIN, an unrecognised
 * payment method, or a date outside the tax year. Those rows import perfectly well and
 * become determination <em>exceptions</em> &mdash; a person needs to act, but the payment
 * is real and the vendor may still have a filing obligation. Rejecting them would silently
 * drop vendors who require a 1099-NEC, which is precisely what the brief says must never
 * happen.
 *
 * <p>The dividing line: <em>reject only what we cannot represent.</em>
 */
public enum RejectionCode {

    /** No declared date format parsed the value, and it is not a plausible Excel serial. */
    UNPARSEABLE_DATE("payment_date", "The date could not be read in this file's format."),

    /** Not a number after stripping currency symbols, separators, and accounting parentheses. */
    UNPARSEABLE_AMOUNT("amount", "The amount is not a number."),

    /**
     * More precision than cents.
     *
     * <p>Rejected rather than rounded. Silently discarding a fraction of a cent makes the
     * imported book disagree with the source by an amount nobody can later account for,
     * and "the totals are off by 3 cents and no one knows why" is a genuinely expensive
     * question to answer in February.
     */
    SUB_CENT_AMOUNT("amount", "The amount has sub-cent precision."),

    /** Blank client reference: the payment cannot be attributed to a payer. */
    MISSING_CLIENT_REF("client_ref", "No client reference."),

    /** A client reference not present in this firm's clients.csv. */
    UNKNOWN_CLIENT_REF("client_ref", "The client reference is not in this export's client list."),

    /**
     * Vendor name <b>and</b> TIN both blank.
     *
     * <p>The only case where a vendor cannot be identified even as an exception, so there
     * is nothing to attach an obligation to. Contrast a blank TIN alone, which is ordinary
     * data and imports normally.
     */
    UNIDENTIFIABLE_VENDOR("vendor_name", "Neither a vendor name nor a TIN was recorded."),

    /** Wrong column count, or an unescaped quote that broke the record. */
    RAGGED_ROW(null, "The row does not match the file's column structure."),

    /**
     * A currency other than USD.
     *
     * <p>Rejected rather than converted: inventing an exchange rate would silently change
     * a filing amount, and the right rate for a tax filing is not a decision this system
     * should be making on its own.
     */
    UNSUPPORTED_CURRENCY("currency", "Only USD is supported."),

    /** A row whose required columns are absent from the file entirely. */
    MISSING_REQUIRED_COLUMN(null, "A required column is missing from this file.");

    private final String columnName;
    private final String humanText;

    RejectionCode(String columnName, String humanText) {
        this.columnName = columnName;
        this.humanText = humanText;
    }

    /** The offending column, where one can be named. */
    public String columnName() {
        return columnName;
    }

    /** Shown on the morning-after page's exception list. */
    public String humanText() {
        return humanText;
    }
}
