package com.soraban.readiness.transmission.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The boundary between this system and the IRS.
 *
 * <p>This package deliberately contains <b>no Spring, no JPA, and no project entity
 * imports</b> &mdash; only value types and one interface. That is what makes the brief's
 * offer real ("we may swap in our own implementation") rather than a claim: an implementer
 * needs nothing from this codebase except this package.
 *
 * <h2>The most important design choice here</h2>
 *
 * <p>Errors are modelled by <b>epistemic class, not by HTTP status</b>. See
 * {@link TransmissionException}. That places the safety policy in the type system, where an
 * implementer cannot omit it: the question a caller must answer is never "what status code
 * was it?" but "could the server have recorded anything?", and only one exception class
 * permits the answer "no".
 */
public interface IrsTransmissionClient {

    /**
     * Submits up to 100 filings, all for the same client.
     *
     * <p>Returns a receipt that says <b>nothing about per-filing acceptance</b> &mdash; that
     * arrives later, individually, via {@link #status}. A receipt means "intake recorded",
     * not "these are filed".
     *
     * @throws TransmissionException.NotDispatched   provably nothing reached the server
     * @throws TransmissionException.Indeterminate   anything after bytes were written
     * @throws TransmissionException.RejectedRequest deterministic validation failure
     * @throws TransmissionException.RateLimited     429; should be unreachable
     */
    Receipt submit(SubmitRequest request);

    /**
     * Asks what became of a submission, by receipt or idempotency key.
     *
     * <p>Repeatable and side-effect free, which is what makes it safe to call after a crash
     * of unknown timing. It is also the <em>only</em> source of the evidence that can
     * release filings from {@code SUBMITTED_UNACKNOWLEDGED}: a
     * {@link StatusResult.Unknown} proves the server never saw the key.
     */
    StatusResult status(StatusQuery query);

    /**
     * @param filings at most 100, all belonging to {@code clientId}
     */
    record SubmitRequest(
            long firmId,
            long clientId,
            int taxYear,
            String idempotencyKey,
            List<FilingPayload> filings
    ) {
        public SubmitRequest {
            if (filings == null || filings.isEmpty()) {
                throw new IllegalArgumentException("a submission must contain at least one filing");
            }
            if (filings.size() > 100) {
                throw new IllegalArgumentException(
                        "a submission may contain at most 100 filings, got " + filings.size());
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotency key is required");
            }
            filings = List.copyOf(filings);
        }
    }

    /**
     * One 1099-NEC.
     *
     * @param clientReference OUR filing id, echoed back on every acknowledgment.
     *                        Acknowledgments are correlated by this and never by array
     *                        position &mdash; positional correlation silently corrupts the
     *                        moment a server reorders, drops, or coalesces entries, and it
     *                        produces wrong data in the right shape, which is the worst
     *                        possible failure for a system whose output is a tax form.
     * @param recipientTin    plaintext, nine digits, decrypted only at this boundary
     */
    record FilingPayload(
            String clientReference,
            int generation,
            String payerEin,
            String recipientTin,
            String recipientTinType,
            String recipientName,
            long nonemployeeCompCents,
            long federalWithheldCents
    ) {
    }

    /** Proof of intake. Says nothing about whether any individual filing was accepted. */
    record Receipt(String receiptId, Instant acceptedAt) {
    }

    /** Look up by receipt when we have one, by idempotency key when we do not. */
    record StatusQuery(long firmId, String idempotencyKey, String receiptId) {

        public static StatusQuery byKey(long firmId, String idempotencyKey) {
            return new StatusQuery(firmId, idempotencyKey, null);
        }
    }

    /**
     * What the endpoint knows about a submission.
     *
     * <p>Sealed, so a caller must handle all three. The distinction between
     * {@link Unknown} and {@link Pending} is the one the entire recovery path depends on,
     * and an endpoint that cannot make it renders failure mode B unrecoverable by any
     * client &mdash; which is the top question in the write-up.
     */
    sealed interface StatusResult {

        /**
         * The server has never seen this key. <b>Positive proof of non-delivery.</b>
         *
         * <p>The only evidence that permits releasing filings from
         * {@code SUBMITTED_UNACKNOWLEDGED} and bumping their generation.
         */
        record Unknown() implements StatusResult {
        }

        /** Recorded, acknowledgments not yet available. Keep polling. */
        record Pending(String receiptId) implements StatusResult {
        }

        /** Recorded and resolved. Acks are per filing, not per batch. */
        record Resolved(String receiptId, List<FilingAck> acks) implements StatusResult {
        }
    }

    /**
     * One filing's outcome.
     *
     * @param clientReference the id we supplied; correlation is by this value alone
     * @param reasonCode      on rejection: {@code MALFORMED_TIN}, {@code TIN_BEGINS_000},
     *                        {@code NON_POSITIVE_AMOUNT}
     */
    record FilingAck(
            String clientReference,
            boolean accepted,
            String irsRecordId,
            String reasonCode,
            String reasonText
    ) {
    }

    /** Convenience for building a deterministic-looking receipt id. */
    static String newReceiptId(UUID seed) {
        return "RCPT-" + seed.toString().substring(0, 18).replace("-", "").toUpperCase();
    }
}
