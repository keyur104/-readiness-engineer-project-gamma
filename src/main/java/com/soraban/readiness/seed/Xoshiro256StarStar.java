package com.soraban.readiness.seed;

import java.util.List;

/**
 * xoshiro256** &mdash; the generator behind every random choice in the seed corpus.
 *
 * <h2>Why not {@code java.util.Random} or {@code SplittableRandom}</h2>
 *
 * <p>The seed generator's contract is that {@code --seed=42} produces a <b>byte-identical</b>
 * corpus on any machine, any JDK, forever. That contract cannot rest on a JDK class whose
 * algorithm is an implementation detail: {@code SplittableRandom}'s split behaviour and
 * {@code Random}'s stream methods are not specified to be stable across releases, and a
 * future JDK could legitimately change them. Golden-file tests would then break for a
 * reason that has nothing to do with this project.
 *
 * <p>xoshiro256** (Blackman &amp; Vigna) is fixed by its specification, is roughly forty
 * lines, passes BigCrush, and is faster than either JDK option. Owning it removes the
 * dependency from the determinism guarantee entirely.
 *
 * <h2>Other things determinism requires</h2>
 *
 * <p>The generator is necessary but nowhere near sufficient. Byte-identical output also
 * requires {@code Locale.ROOT} on every format call, an explicit {@code '\n'} line ending
 * (never {@code System.lineSeparator()}, which alone would make Windows and CI output
 * differ), UTF-8 without a BOM, fixed column order, {@code BigDecimal.toPlainString()},
 * and word corpora committed to the repository rather than drawn from a faker library
 * whose data changes between versions. All of those are pinned in the writer, and a
 * golden-bytes test asserts the SHA-256 of the first 10,000 lines for seed 42.
 *
 * <p>Not thread-safe by design &mdash; each generation stream is owned by exactly one
 * thread. See {@link RandomStreams} for how that stays true while generating in parallel.
 */
public final class Xoshiro256StarStar {

    private long s0;
    private long s1;
    private long s2;
    private long s3;

    /**
     * Seeds all four words from consecutive SplitMix64 draws, which is the initialization
     * the xoshiro authors specify. Seeding the state directly from a small integer would
     * leave it mostly zero bits and degrade early output.
     */
    public Xoshiro256StarStar(long seed) {
        SplitMix64 expander = new SplitMix64(seed);
        this.s0 = expander.next();
        this.s1 = expander.next();
        this.s2 = expander.next();
        this.s3 = expander.next();
    }

    public long nextLong() {
        long result = Long.rotateLeft(s1 * 5, 7) * 9;

        long t = s1 << 17;
        s2 ^= s0;
        s3 ^= s1;
        s1 ^= s2;
        s0 ^= s3;
        s2 ^= t;
        s3 = Long.rotateLeft(s3, 45);

        return result;
    }

    /**
     * Uniform in {@code [0, bound)}.
     *
     * <p>Uses Lemire's multiply-shift with rejection. The naive {@code % bound} is biased
     * whenever {@code bound} is not a power of two, and while that bias is invisible in
     * most uses, here it would systematically skew the distribution of vendor counts and
     * payment amounts across a million rows &mdash; a corpus that is subtly wrong in a way
     * that is hard to notice and harder to explain.
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, was " + bound);
        }
        long random = nextLong() >>> 32;
        long multiplied = random * bound;
        long low = multiplied & 0xFFFFFFFFL;
        if (low < bound) {
            long threshold = Integer.toUnsignedLong(-bound) % bound;
            while (low < threshold) {
                random = nextLong() >>> 32;
                multiplied = random * bound;
                low = multiplied & 0xFFFFFFFFL;
            }
        }
        return (int) (multiplied >>> 32);
    }

    /** Uniform in {@code [origin, bound)}. */
    public int nextInt(int origin, int bound) {
        return origin + nextInt(bound - origin);
    }

    /** Uniform in {@code [origin, bound)} for longs, via {@link #nextInt} on the span. */
    public long nextLong(long origin, long bound) {
        long span = bound - origin;
        if (span <= 0) {
            throw new IllegalArgumentException("empty range [" + origin + ", " + bound + ")");
        }
        // 53 bits of double precision is ample for every range this generator produces
        // (the largest is a payment amount in cents) and keeps the code short.
        return origin + (long) (nextDouble() * span);
    }

    /**
     * Uniform in {@code [0, 1)}. Takes the top 53 bits, which is the standard construction
     * and the only one that yields a uniform double.
     */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** True with probability {@code p}. */
    public boolean nextBoolean(double p) {
        return nextDouble() < p;
    }

    /**
     * Standard normal via Box-Muller.
     *
     * <p>Deliberately does not cache the second variate. Caching would make a draw depend
     * on whether a previous call happened to be odd or even, which quietly couples
     * unrelated call sites &mdash; add one draw anywhere upstream and every subsequent
     * value shifts. Determinism is worth more here than the halved cost of a
     * {@code Math.log}.
     */
    public double nextGaussian() {
        double u1 = nextDouble();
        double u2 = nextDouble();
        // u1 can be exactly 0, and log(0) is -infinity; nudge it into (0, 1).
        if (u1 < 1e-300) {
            u1 = 1e-300;
        }
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    /**
     * Log-normal, used for rows-per-client.
     *
     * <p>A realistic CPA book is not uniform: most clients are small and a handful are
     * very large. That shape matters beyond realism &mdash; it is what makes chunked,
     * client-parallel import scheduling non-trivial, and what stresses the batch planner
     * with one client big enough to fill many batches.
     */
    public long nextLogNormal(double logMean, double logStdDev, long min, long max) {
        double value = Math.exp(logMean + logStdDev * nextGaussian());
        return Math.clamp((long) value, min, max);
    }

    /** Uniformly chooses one element. */
    public <T> T pick(List<T> options) {
        return options.get(nextInt(options.size()));
    }

    /** Uniformly chooses one element. */
    public <T> T pick(T[] options) {
        return options[nextInt(options.length)];
    }

    /**
     * Chooses an index according to cumulative weights, where {@code cumulative} is
     * non-decreasing and its last element is the total. Used for the payment-method mix
     * (check 35% / ACH 30% / card 25% / wire 5% / cash 3% / unknown 2%), where the exact
     * proportions are load-bearing for Part 2 rather than cosmetic: the card share is what
     * exercises the 1099-K exclusion at scale.
     */
    public int pickWeighted(int[] cumulative) {
        int target = nextInt(cumulative[cumulative.length - 1]);
        for (int i = 0; i < cumulative.length; i++) {
            if (target < cumulative[i]) {
                return i;
            }
        }
        return cumulative.length - 1;
    }

    /**
     * Fisher-Yates, in place. Used to scatter planted fixture cases through a client's
     * payment list so they do not appear as a recognisable contiguous block &mdash; the
     * six required cases have to look exactly like ordinary data.
     */
    public <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
