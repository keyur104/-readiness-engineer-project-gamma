package com.soraban.readiness.ledger;

import java.util.Locale;

/**
 * What kind of ledger entry a row represents.
 *
 * <h2>Why this is a column and not inferred from the sign of the amount</h2>
 *
 * <p>Determination sums signed amounts, so strictly it could work from the sign alone. Two
 * reasons it does not:
 *
 * <ol>
 *   <li><b>A negative amount is ambiguous.</b> It could be a genuine reversal, a refund
 *       from the vendor, a voided cheque, or a bookkeeper's data-entry sign error. Those
 *       are different facts and a human resolving an exception needs to know which.</li>
 *   <li><b>Explainability.</b> The brief requires showing "which payments counted, which
 *       didn't and why". A row that explains itself as <em>"reversal of txn QB-88213"</em>
 *       is useful; one that says <em>"negative"</em> is not.</li>
 * </ol>
 *
 * <p>The paired {@code reverses_source_txn_id} column links a reversal to what it reverses.
 * Determination never needs that link to compute the number &mdash; it just sums &mdash;
 * but "which payment did this reverse?" is the first question anyone asks about the
 * $800-gross/$250-net case, so the answer is stored rather than reconstructed.
 */
public enum EntryType {

    /** An ordinary outbound payment. */
    PAYMENT,

    /** Reverses an earlier payment, typically same-year and often in December. */
    REVERSAL,

    /** Money returned by the vendor. Reduces the net exactly as a reversal does. */
    REFUND,

    /**
     * A cheque or payment cancelled before it settled. Excluded outright rather than
     * netted &mdash; it never moved money, so counting it and then subtracting it would
     * inflate both the gross and the reversal totals shown in the explanation.
     */
    VOID;

    /**
     * Maps raw export text, defaulting sensibly.
     *
     * <p>When the column is absent or blank, a negative amount is read as
     * {@link #REVERSAL} and anything else as {@link #PAYMENT}. Hand-maintained
     * spreadsheets frequently have no entry-type column at all, so this default is what
     * makes them usable &mdash; but the resulting explanation is correspondingly weaker,
     * which is itself an argument for exports that carry the column.
     */
    public static EntryType fromRaw(String raw, long amountCents) {
        if (raw == null || raw.isBlank()) {
            return amountCents < 0 ? REVERSAL : PAYMENT;
        }

        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "REVERSAL", "REVERSE", "REVERSED", "CREDIT_MEMO", "CREDIT" -> REVERSAL;
            case "REFUND", "REFUNDED", "RETURN" -> REFUND;
            case "VOID", "VOIDED", "CANCELLED", "CANCELED" -> VOID;
            case "PAYMENT", "PAYMENT_", "BILL_PAYMENT", "CHECK", "SPEND_MONEY" -> PAYMENT;
            default -> amountCents < 0 ? REVERSAL : PAYMENT;
        };
    }

    /** Whether this entry reduces the net rather than adding to it. */
    public boolean isReversing() {
        return this == REVERSAL || this == REFUND;
    }
}
