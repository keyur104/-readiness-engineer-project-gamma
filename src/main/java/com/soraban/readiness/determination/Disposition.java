package com.soraban.readiness.determination;

/**
 * Why one payment did or did not count toward a vendor's reportable total.
 *
 * <p>Stored per payment by the determination pass, which is what makes the brief's
 * requirement &mdash; <em>"show which payments counted, which didn't and why"</em> &mdash;
 * a lookup rather than a recomputation.
 *
 * <h2>The order of these constants is the rule precedence</h2>
 *
 * <p>Classification takes the first matching rule, and the sequence is load-bearing rather
 * than arbitrary. The case that proves it:
 *
 * <p><b>A refund of a card payment carries {@code payment_method = credit_card} itself.</b>
 * Because {@link #EXCLUDED_CARD_TPSO} is tested before {@link #COUNTED_REVERSAL}, that
 * refund is excluded symmetrically with the payment it reverses. An implementation that
 * checked "is the amount negative?" first would <em>count</em> the refund while
 * <em>excluding</em> the original payment &mdash; dragging the vendor's total negative and
 * producing a reportable amount lower than any payment they actually received.
 *
 * <p>That bug is invisible in aggregate: the total looks plausible, no exception fires, and
 * the vendor simply gets no form. Classifying on the row's own payment method first makes
 * it impossible.
 */
public enum Disposition {

    /**
     * Paid outside the tax year.
     *
     * <p>Tested first so out-of-year payments are <em>visible as excluded</em> in the
     * vendor's explanation rather than absent from it. A payment that silently vanishes is
     * indistinguishable from a payment that was never imported, and the difference matters
     * when someone is reconciling against the client's own books.
     */
    EXCLUDED_OUT_OF_TAX_YEAR(false, "Paid outside the tax year."),

    /**
     * Voided before it settled.
     *
     * <p>Excluded outright rather than netted, because it never moved money. Counting it
     * and then subtracting it would inflate both the gross and the reversal figures shown
     * in the explanation, making the arithmetic on screen look wrong even though the total
     * is right.
     */
    EXCLUDED_VOID(false, "Voided before it settled; no money moved."),

    /** Not for services, so not nonemployee compensation. Rent and merchandise land here. */
    EXCLUDED_NON_SERVICES(false, "Not for services, so not nonemployee compensation."),

    /**
     * Card or third-party network.
     *
     * <p>The payment processor reports these on Form 1099-K, so they count toward neither
     * the threshold nor Box 1. Reporting them again here would report the same income twice
     * under the vendor's TIN and trigger an under-reporting notice against the contractor.
     */
    EXCLUDED_CARD_TPSO(false, "Paid by card or third-party network; reported by the processor on Form 1099-K."),

    /** A zero-amount line. Real, harmless, and must not become a $0 filing. */
    EXCLUDED_ZERO_AMOUNT(false, "Zero amount."),

    /** A reversal or refund. Reduces the year's net. */
    COUNTED_REVERSAL(true, "Reversal or refund; reduces the year's net."),

    /** An ordinary payment for services. */
    COUNTED(true, "Counted toward the threshold.");

    private final boolean counted;
    private final String humanText;

    Disposition(boolean counted, String humanText) {
        this.counted = counted;
        this.humanText = humanText;
    }

    /** Whether this payment's amount contributes to the reportable total. */
    public boolean isCounted() {
        return counted;
    }

    /** Mirrors {@code app.reason_code.human_text}; the page renders the database's copy. */
    public String humanText() {
        return humanText;
    }
}
