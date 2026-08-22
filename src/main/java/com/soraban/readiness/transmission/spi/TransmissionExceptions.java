package com.soraban.readiness.transmission.spi;

/**
 * Transmission failures, classified by <b>what the caller may conclude about the server's
 * state</b> rather than by what went wrong on the wire.
 *
 * <h2>Why this taxonomy and not HTTP status codes</h2>
 *
 * <p>The only question that matters after a failed submission is: <em>could the server have
 * recorded anything?</em> An HTTP status is a poor proxy for that. A 500 might mean the
 * request was rejected at the front door or that every filing was written and the response
 * died on the way back &mdash; and under failure mode B, an error is returned specifically
 * <em>after</em> everything was recorded.
 *
 * <p>So the type system is made to carry the safety policy. There is exactly one class that
 * permits the conclusion "nothing happened", and an implementer has to reach for it
 * deliberately. Everything else defaults to {@link Indeterminate}, which means a future
 * HTTP client that maps unrecognised exceptions to the default is <b>safe by accident</b>.
 * That is the right direction for a default to fail in.
 */
public final class TransmissionExceptions {

    private TransmissionExceptions() {
    }

    /** Base type; never thrown directly. */
    public abstract static class TransmissionException extends RuntimeException {
        protected TransmissionException(String message) {
            super(message);
        }

        protected TransmissionException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * Whether the server might hold the filings from this request.
         *
         * <p>The single question the dispatcher asks. {@code false} only for
         * {@link NotDispatched}.
         */
        public abstract boolean serverMayHaveRecorded();
    }

    /**
     * <b>Provably</b> nothing reached the server.
     *
     * <p>Connection refused, DNS failure, TLS handshake failure, or a local rate refusal --
     * cases where the request demonstrably never left. The batch stays {@code SEALED} and
     * its filings stay {@code BATCHED}, because releasing them is safe.
     *
     * <p>This is the only class that permits that conclusion. Reaching for it when unsure
     * is the one mistake that can produce a duplicate filing.
     */
    public static sealed class NotDispatched extends TransmissionException
            permits BudgetExhausted {
        public NotDispatched(String message) {
            super(message);
        }

        public NotDispatched(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public final boolean serverMayHaveRecorded() {
            return false;
        }
    }

    /**
     * Anything at all after bytes were written. <b>This is the default.</b>
     *
     * <p>5xx, read timeout, socket reset, unparseable response, or an exception nobody
     * anticipated. The batch becomes (or stays) {@code DISPATCHED} and its filings stay
     * {@code SUBMITTED_UNACKNOWLEDGED} &mdash; the IRS may hold them, and only a status call
     * can settle it.
     *
     * <p><b>Unknown implies Indeterminate.</b> If you do not know, you do not know.
     */
    public static final class Indeterminate extends TransmissionException {
        public Indeterminate(String message) {
            super(message);
        }

        public Indeterminate(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean serverMayHaveRecorded() {
            return true;
        }
    }

    /**
     * A deterministic validation failure: retrying the identical request will not help.
     *
     * <p>Still treated as possibly-recorded. A 4xx <em>probably</em> means nothing was
     * written, but "probably" is not a basis for re-sending a tax filing under a new key,
     * and the cost of being conservative is one status call.
     */
    public static final class RejectedRequest extends TransmissionException {
        private final String code;

        public RejectedRequest(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }

        @Override
        public boolean serverMayHaveRecorded() {
            return true;
        }
    }

    /**
     * A 429 from the endpoint.
     *
     * <p>Should be unreachable: the rate budget is enforced before every call, in the same
     * transaction as the state change. Seeing one means <em>our</em> accounting is wrong, so
     * it raises a high-severity attention item rather than being quietly retried.
     */
    public static final class RateLimited extends TransmissionException {
        public RateLimited(String message) {
            super(message);
        }

        @Override
        public boolean serverMayHaveRecorded() {
            return false;
        }
    }

    /**
     * Local refusal: our own budget is exhausted, so no call was made.
     *
     * <p>Distinct from {@link RateLimited}, which is the endpoint refusing us. This one is
     * expected and routine &mdash; the batch is simply rescheduled for when the window
     * reopens.
     */
    public static final class BudgetExhausted extends NotDispatched {
        // `sealed`/`permits` on NotDispatched keeps the "provably nothing was sent" claim
        // closed: no future subclass can quietly assert it without being listed here.
        private final java.time.Instant retryAt;

        public BudgetExhausted(java.time.Instant retryAt) {
            super("firm rate budget exhausted; next slot at " + retryAt);
            this.retryAt = retryAt;
        }

        public java.time.Instant retryAt() {
            return retryAt;
        }
    }
}
