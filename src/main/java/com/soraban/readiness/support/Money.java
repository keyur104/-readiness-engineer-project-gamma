package com.soraban.readiness.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money as a {@code long} count of cents. Never a {@code double}, never a {@code float},
 * and {@link BigDecimal} only at the CSV and presentation boundaries.
 *
 * <h2>Why integer cents rather than {@code NUMERIC}/{@code BigDecimal} throughout</h2>
 *
 * <ul>
 *   <li><b>The threshold comparison becomes exact.</b> "$600 or more is inclusive"
 *       reduces to {@code reportableCents >= 60_000} &mdash; an integer comparison. There
 *       is no epsilon to choose and no rounding mode to argue about. Under {@code double},
 *       the perfectly ordinary payment sequence 199.99 + 200.01 + 200.00 sums to
 *       600.0000000000001, and a naive {@code >= 600.0} would pass for the wrong reason
 *       while a {@code <= 600} check elsewhere in the same system would fail.</li>
 *   <li><b>Summation is free.</b> The determination pass aggregates roughly a million
 *       amounts; {@code sum(bigint)} is exact integer arithmetic with no scale
 *       negotiation and no per-row object allocation.</li>
 * </ul>
 *
 * <p>The invariant is enforced, not merely documented: {@code NoFloatingPointMoneyTest}
 * scans {@code information_schema.columns} for {@code real}/{@code double precision} and
 * fails the build if one ever appears in the schema.
 */
public final class Money {

    /** "$600 or more" for tax year 2025, as cents. Bound from configuration, not inlined into SQL. */
    public static final long ZERO = 0L;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    /**
     * Parses a decimal money string into cents.
     *
     * <p>Uses {@code new BigDecimal(String)} &mdash; never {@code Double.parseDouble} and
     * never {@code new BigDecimal(double)}, both of which introduce binary representation
     * error before the value is even stored.
     *
     * <p>Sub-cent input <b>throws</b> rather than rounding. A bookkeeper's export
     * containing {@code 12.345} is a data problem someone should see, and silently
     * truncating it would make the books disagree with the source by an amount nobody
     * can later account for. The importer turns this into a {@code SUB_CENT_AMOUNT}
     * row rejection.
     *
     * @throws ArithmeticException if the value has more than two decimal places
     * @throws NumberFormatException if the text is not a valid decimal
     */
    public static long parseToCents(String text) {
        BigDecimal decimal = new BigDecimal(text.strip());
        return toCents(decimal);
    }

    /**
     * Converts a {@link BigDecimal} to cents exactly.
     *
     * @throws ArithmeticException if the value has sub-cent precision
     */
    public static long toCents(BigDecimal decimal) {
        // movePointRight(2) then longValueExact(): the "exact" call is the point. It
        // throws on any fractional remainder rather than quietly discarding it.
        return decimal.movePointRight(2).longValueExact();
    }

    /** Cents back to a {@link BigDecimal} with scale 2, for display and CSV output. */
    public static BigDecimal toDecimal(long cents) {
        return BigDecimal.valueOf(cents).divide(HUNDRED, 2, RoundingMode.UNNECESSARY);
    }

    /**
     * Formats cents for human display: {@code 82500} to {@code "$825.00"}.
     * Negative amounts render with a leading minus, e.g. {@code "-$550.00"}, because a
     * reversal reading as {@code "$(550.00)"} is an accounting convention this page's
     * readers do not need decoded for them.
     */
    public static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long absolute = Math.abs(cents);
        return "%s$%d.%02d".formatted(sign, absolute / 100, absolute % 100);
    }

    /** Formats cents without the currency symbol, for CSV emission. */
    public static String toPlainString(long cents) {
        return toDecimal(cents).toPlainString();
    }
}
