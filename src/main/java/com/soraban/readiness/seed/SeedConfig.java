package com.soraban.readiness.seed;

import java.nio.file.Path;
import java.util.List;

/**
 * Shape of the corpus to generate.
 *
 * <p>The distribution constants are not decoration. Each one exists to stress a specific
 * part of the system, and the comment on each says which &mdash; a corpus of uniformly
 * average clients would let a great many bugs through while looking thorough.
 *
 * @param seed        root seed; the same value reproduces byte-identical output
 * @param firms       firm slugs, in order
 * @param clientCount total business clients across all firms
 * @param targetLines approximate total ledger lines
 * @param taxYear     the filing year
 * @param outputDir   where the export directories are written
 * @param revision    0 for the original export; N &gt; 0 applies the Nth scripted revision
 * @param gzip        compress output (the importer sniffs the magic bytes)
 */
public record SeedConfig(
        long seed,
        List<String> firms,
        int clientCount,
        long targetLines,
        int taxYear,
        Path outputDir,
        int revision,
        boolean gzip
) {

    public static final List<String> DEFAULT_FIRMS = List.of("northstar", "harborline");

    public static SeedConfig defaults(Path outputDir) {
        return new SeedConfig(42L, DEFAULT_FIRMS, 500, 1_000_000L, 2025, outputDir, 0, false);
    }

    public SeedConfig withSeed(long newSeed) {
        return new SeedConfig(newSeed, firms, clientCount, targetLines, taxYear, outputDir, revision, gzip);
    }

    public SeedConfig withRevision(int newRevision) {
        return new SeedConfig(seed, firms, clientCount, targetLines, taxYear, outputDir, newRevision, gzip);
    }

    // ---------------------------------------------------------------------------------
    // Distribution constants
    // ---------------------------------------------------------------------------------

    /**
     * Rows per client are log-normal, not uniform.
     *
     * <p>A real CPA book is mostly small clients with a handful of very large ones. That
     * shape is what makes client-parallel import scheduling non-trivial, and it guarantees
     * at least one client big enough to fill many transmission batches on its own &mdash;
     * which is the case that exposes the brief's real bottleneck, since a submission call
     * may contain at most 100 filings and they must all belong to one client.
     */
    public static final double ROWS_PER_CLIENT_LOG_MEAN = 7.3;   // e^7.3 ~= 1480
    public static final double ROWS_PER_CLIENT_LOG_STDDEV = 0.85;
    public static final long ROWS_PER_CLIENT_MIN = 40;
    public static final long ROWS_PER_CLIENT_MAX = 25_000;

    /** Vendors per client. Payment counts within a client follow a Zipf-like skew. */
    public static final int VENDORS_PER_CLIENT_MEAN = 40;
    public static final int VENDORS_PER_CLIENT_MIN = 5;
    public static final int VENDORS_PER_CLIENT_MAX = 1_500;

    /**
     * Roughly how many payments one contractor receives from one client in a year.
     *
     * <p>This is what makes the vendor count scale with the client's activity, and it exists
     * because the first version did not. Vendor count was drawn independently of row count, so
     * a client with 15,000 payments still got about 40 contractors — and the Zipf head then
     * handed one of them <b>3,461 payments totalling $2.5 million</b>. Every rule applied to
     * that vendor was correct; the vendor was simply not a thing that exists.
     *
     * <p>Eighteen is a deliberate middle: a contractor on a monthly retainer gets 12, one paid
     * per job might get 40, a weekly cleaner 52. The Zipf skew below still spreads the actual
     * counts widely around it, which is the point — a real book has a few vendors paid
     * constantly and a long tail paid once.
     */
    public static final int PAYMENTS_PER_VENDOR_TARGET = 18;

    /**
     * Zipf exponent for how a client's payments spread across its contractors.
     *
     * <p>Was effectively 1.0 — a pure harmonic series, where the top vendor takes
     * {@code 1/H(n)} of everything: about 23% of a 40-vendor client's entire year. Real books
     * are skewed, but not that skewed.
     *
     * <p>At 0.65 the head is still clearly the busiest supplier and the tail still gets paid
     * once or twice, so the threshold cases the corpus exists to produce are unaffected — the
     * distribution just stops being absurd at the top.
     */
    public static final double VENDOR_SHARE_EXPONENT = 0.65;

    /**
     * Hard ceiling on payments from one client to one contractor in a year.
     *
     * <p>A backstop rather than the mechanism: the scaling above should keep counts sane on
     * its own, but a very large client with an unlucky draw could still concentrate. 260 is
     * about one payment per working day, which is the most a genuine contractor relationship
     * would plausibly produce.
     */
    public static final long MAX_PAYMENTS_PER_VENDOR = 260;

    /**
     * TIN availability. The 12% blank share is deliberately large: a no-TIN vendor above
     * threshold is a filing obligation that cannot transmit, which is the exception type
     * the morning-after page most needs to surface, and a corpus with only a handful would
     * barely exercise it.
     */
    public static final double TIN_PRESENT_RATE = 0.85;
    public static final double TIN_BLANK_RATE = 0.12;
    public static final double TIN_MALFORMED_RATE = 0.03;

    /**
     * Payment methods in weight order, paired with {@link #PAYMENT_METHOD_CUMULATIVE}.
     *
     * <p><b>Explicit, rather than relying on {@code PaymentMethod.values()}.</b> An earlier
     * version indexed the enum's declaration order with these weights, and the two silently
     * disagreed: the 18% intended for credit card landed on wire, card + TPSO came out at 8%
     * instead of 25%, and the corpus quietly under-exercised the Form 1099-K exclusion by a
     * factor of three.
     *
     * <p>Nothing failed. The generator ran, the import ran, determination ran, and every
     * planted fixture passed &mdash; because the fixtures pin specific vendors, not the
     * background distribution. It surfaced only when the disposition histogram was compared
     * against the intended mix.
     *
     * <p>Ordinal-coupled arrays are exactly the kind of thing that breaks when someone
     * reorders an enum for readability, and breaks without any symptom. Naming the constants
     * makes the pairing checkable by eye and impossible to shuffle by accident.
     */
    public static final com.soraban.readiness.ledger.PaymentMethod[] PAYMENT_METHOD_ORDER = {
            com.soraban.readiness.ledger.PaymentMethod.CHECK,
            com.soraban.readiness.ledger.PaymentMethod.ACH,
            com.soraban.readiness.ledger.PaymentMethod.CREDIT_CARD,
            com.soraban.readiness.ledger.PaymentMethod.TPSO,
            com.soraban.readiness.ledger.PaymentMethod.WIRE,
            com.soraban.readiness.ledger.PaymentMethod.CASH,
            com.soraban.readiness.ledger.PaymentMethod.UNKNOWN
    };

    /**
     * Cumulative weights: check 35%, ACH 30%, credit card 18%, TPSO 7%, wire 5%, cash 3%,
     * unknown 2%.
     *
     * <p>The card + TPSO share of 25% is load-bearing rather than cosmetic: it is what
     * exercises the Form 1099-K exclusion at scale, and it puts a meaningful number of
     * vendors near the threshold boundary from either side.
     */
    public static final int[] PAYMENT_METHOD_CUMULATIVE = {35, 65, 83, 90, 95, 98, 100};

    /**
     * Share of rows that are reversals or refunds, and how many of those fall in December.
     *
     * <p>The December concentration matches the real seasonal pattern (year-end cleanup)
     * and stresses Part 2 case 2 &mdash; the $800-gross/$250-net vendor whose reversal is
     * the last thing that happens to them all year.
     */
    public static final double REVERSAL_RATE = 0.04;
    public static final double REVERSAL_IN_DECEMBER_RATE = 0.60;

    /**
     * Share of rows dated outside the tax year.
     *
     * <p>Present so the explanation has something to exclude. A vendor whose payments
     * straddle the year boundary proves the pass filters by year rather than summing
     * everything it can see &mdash; and proves the excluded rows still appear in the
     * explanation rather than silently vanishing.
     */
    public static final double OUT_OF_YEAR_RATE = 0.06;

    /**
     * Share of clients that switched accounting systems mid-year and therefore appear in
     * two files.
     *
     * <p>Realistic, and it is exactly what stresses tombstone scoping: a QuickBooks export
     * must not delete that client's spreadsheet rows just because they are absent from it.
     */
    public static final double SYSTEM_SWITCH_RATE = 0.02;

    /** Share of rows carrying a deliberate defect. */
    public static final double DEFECT_RATE = 0.008;

    /** Backup withholding, when applied, is 24% -- the statutory rate. */
    public static final double BACKUP_WITHHOLDING_RATE = 0.24;

    /** Share of vendors subject to backup withholding. */
    public static final double VENDOR_WITHHOLDING_RATE = 0.015;

    /** Share of payments that are not for services. */
    public static final double NON_SERVICES_RATE = 0.18;

    /** Average bytes per rendered row, for pre-sizing buffers and reporting progress. */
    public static final int APPROX_BYTES_PER_ROW = 140;
}
