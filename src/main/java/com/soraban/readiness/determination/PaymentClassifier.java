package com.soraban.readiness.determination;

import com.soraban.readiness.ledger.EntryType;
import com.soraban.readiness.ledger.ExpenseClass;

/**
 * The determination rules, in plain Java, applied to one payment at a time.
 *
 * <h2>Why this exists when the real engine is SQL</h2>
 *
 * <p>This is not the production path &mdash; the pass that runs over a million rows is
 * set-based SQL, because hydrating that many rows over JDBC would blow the one-minute
 * budget on wire time alone. This class exists as an <b>independent second implementation</b>,
 * and its value comes entirely from being independent.
 *
 * <p>The strongest correctness argument available for a rules engine is not "the tests
 * pass" &mdash; tests encode the same understanding the implementation does, so a
 * misunderstanding produces a passing test. It is <b>two implementations, written from
 * different angles, that must agree on every input.</b> A property test pushes tens of
 * thousands of generated payments through both this classifier and the SQL and asserts the
 * dispositions match. For them to agree while both being wrong, the same mistake would have
 * to be made twice, in two different languages, at two different levels of abstraction.
 *
 * <p>It also makes the six required cases testable in milliseconds with no database at all,
 * which is what allows the rules to be iterated on quickly and the boundary conditions to
 * be explored exhaustively rather than sampled.
 *
 * <p>Deliberately stateless and free of any Spring, JDBC, or persistence dependency: it
 * takes primitives and returns an enum. Anything it needed from the outside world would be
 * a way for the two implementations to share a bug.
 */
public final class PaymentClassifier {

    private PaymentClassifier() {
    }

    /**
     * Classifies one payment. Mirrors, exactly, the {@code CASE} expression in
     * {@code DeterminationEngine}'s classification CTE &mdash; including the order.
     *
     * @param taxYear      the year being determined
     * @param paymentYear  the year this payment falls in
     * @param entryType    payment / reversal / refund / void
     * @param expenseClass services or otherwise
     * @param cardOrTpso   whether the processor reports this on Form 1099-K
     * @param amountCents  signed; negative for reversals and refunds
     */
    public static Disposition classify(int taxYear,
                                       int paymentYear,
                                       EntryType entryType,
                                       ExpenseClass expenseClass,
                                       boolean cardOrTpso,
                                       long amountCents,
                                       long withholdingCents) {
        // The order below IS the rule precedence. See Disposition's class note for why the
        // card check must precede the reversal check.
        if (paymentYear != taxYear) {
            return Disposition.EXCLUDED_OUT_OF_TAX_YEAR;
        }
        if (entryType == EntryType.VOID) {
            return Disposition.EXCLUDED_VOID;
        }
        if (!expenseClass.isReportableAsNec()) {
            return Disposition.EXCLUDED_NON_SERVICES;
        }
        if (cardOrTpso) {
            return Disposition.EXCLUDED_CARD_TPSO;
        }
        if (amountCents == 0 && withholdingCents == 0) {
            return Disposition.EXCLUDED_ZERO_AMOUNT;
        }
        if (amountCents < 0) {
            return Disposition.COUNTED_REVERSAL;
        }
        return Disposition.COUNTED;
    }

    /** The signed amount this payment contributes to the vendor's reportable total. */
    public static long countedCents(Disposition disposition, long amountCents) {
        return disposition.isCounted() ? amountCents : 0L;
    }

    /**
     * Whether backup withholding on this payment counts toward Box 4.
     *
     * <p>Restricted to in-year, non-card payments, for consistency with the reportable
     * basis. Withholding on a card payment would be incoherent anyway &mdash; the processor
     * is reporting that payment, so the withholding belongs to their filing, not ours.
     */
    public static long countedWithholding(int taxYear, int paymentYear,
                                          boolean cardOrTpso, long withholdingCents) {
        if (paymentYear != taxYear || cardOrTpso) {
            return 0L;
        }
        return withholdingCents;
    }
}
