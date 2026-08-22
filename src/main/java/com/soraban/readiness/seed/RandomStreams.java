package com.soraban.readiness.seed;

import java.nio.charset.StandardCharsets;

/**
 * Derives independent random streams from a single root seed, keyed by a hierarchical
 * <em>string path</em>.
 *
 * <pre>
 *   streams.get("firm:northstar")
 *   streams.get("firm:northstar/client:0142")
 *   streams.get("firm:northstar/client:0142/payments")
 *   streams.get("firm:northstar/client:0142/vendor:07")
 *   streams.get("firm:northstar/defects")
 * </pre>
 *
 * <p>This is the single most important design decision in the seed generator, and it is
 * worth more than the choice of PRNG. Three properties fall out of it:
 *
 * <h2>1. Parallel generation stays byte-identical</h2>
 *
 * <p>Every client draws from its own stream, so clients can be generated concurrently into
 * separate buffers and concatenated in client order. With one shared generator, output
 * would depend on thread interleaving and the corpus would differ run to run.
 *
 * <h2>2. The corpus survives code changes</h2>
 *
 * <p>This is the property that actually matters over the life of the project. With a
 * single sequential stream, adding one new random draw anywhere &mdash; a memo generator,
 * an extra address field &mdash; shifts every subsequent value and reshuffles the entire
 * corpus. Every golden test breaks, every fixture moves, and the diff is unreviewable.
 * With path-keyed streams, a new draw creates a <em>new path</em> and touches nothing that
 * already exists.
 *
 * <h2>3. Any slice is independently reproducible</h2>
 *
 * <p>Client 0142 can be regenerated on its own, without generating the other 499, because
 * its stream depends only on its path and the root seed. That makes debugging a single
 * planted fixture case tractable.
 *
 * <p>Derivation is {@code SplitMix64.mix(rootSeed ^ fnv1a64(path))}. FNV-1a is used for
 * the path hash rather than {@link String#hashCode()} because {@code hashCode} is only
 * 32 bits and collides readily across similar paths, and rather than SHA-256 because this
 * needs to be cheap and needs no cryptographic property &mdash; only good dispersion.
 * SplitMix64's finalizer then avalanches the result so that adjacent paths
 * ({@code client:0142} and {@code client:0143}) produce completely unrelated streams.
 */
public final class RandomStreams {

    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private final long rootSeed;

    public RandomStreams(long rootSeed) {
        this.rootSeed = rootSeed;
    }

    /**
     * A fresh generator for {@code path}. Calling this twice with the same path returns
     * two generators that produce the same sequence &mdash; that is the point, not a bug:
     * it is what makes a slice independently reproducible.
     */
    public Xoshiro256StarStar get(String path) {
        return new Xoshiro256StarStar(seedFor(path));
    }

    /** The derived seed for a path. Exposed so tests can assert stream independence. */
    public long seedFor(String path) {
        return SplitMix64.mix(rootSeed ^ fnv1a64(path));
    }

    public long rootSeed() {
        return rootSeed;
    }

    /**
     * FNV-1a over the UTF-8 bytes of the path.
     *
     * <p>Hashing bytes rather than {@code char}s keeps the value identical regardless of
     * platform encoding &mdash; the same reason the writer pins UTF-8 and {@code '\n'}.
     */
    static long fnv1a64(String path) {
        long hash = FNV_OFFSET_BASIS;
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
