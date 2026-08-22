package com.soraban.readiness.seed;

/**
 * SplitMix64 &mdash; a 64-bit mixing function used here purely to expand seeds.
 *
 * <p>Its job in this project is to turn a single {@code long} into well-distributed state
 * for {@link Xoshiro256StarStar}. Seeding a xoshiro generator directly from a small
 * integer is a known mistake: a state that is mostly zero bits produces a long run of
 * poor-quality output before it recovers. SplitMix64 avalanches a small seed into 64
 * well-mixed bits, so every derived stream starts from good state.
 *
 * <p>Implemented here rather than taken from a library because the whole point of the
 * seed generator is that <b>the same seed reproduces byte-identical output, forever, on
 * any machine and any JDK</b>. That guarantee cannot rest on a dependency whose
 * implementation could be tuned in a future release. It is about twenty lines and the
 * algorithm is fixed by its specification.
 *
 * <p>The constants are from Steele, Lea &amp; Flood's SplittableRandom (2014); the golden
 * gamma {@code 0x9E3779B97F4A7C15} is 2<sup>64</sup>/&phi;.
 */
public final class SplitMix64 {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /** Advances and returns the next value. */
    public long next() {
        state += GOLDEN_GAMMA;
        return mix(state);
    }

    /**
     * The stateless finalizer. Exposed because seed derivation wants to mix a value
     * without carrying a generator around.
     */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * MIX_A;
        z = (z ^ (z >>> 27)) * MIX_B;
        return z ^ (z >>> 31);
    }
}
