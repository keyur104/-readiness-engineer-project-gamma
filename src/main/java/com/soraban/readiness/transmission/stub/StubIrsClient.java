package com.soraban.readiness.transmission.stub;

import com.soraban.readiness.transmission.spi.IrsTransmissionClient;
import com.soraban.readiness.transmission.spi.TransmissionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.soraban.readiness.config.ConditionalOnDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake IRS that behaves like the real thing in the ways that matter.
 *
 * <p>The stub itself is not what is being evaluated &mdash; how the system behaves
 * <em>against</em> it is. So it is deliberately small, and its interesting properties are
 * the ones that make the system's behaviour observable:
 *
 * <h2>The stub records; the test judges</h2>
 *
 * <p>{@code irs_stub.recorded_filing} has <b>no uniqueness constraint</b>. If the stub
 * refused duplicates, a duplicate-producing bug in the transmitter would surface as a stub
 * exception and the test would pass for the wrong reason &mdash; having proved only that the
 * stub enforces something. Instead it faithfully records whatever it is told, including
 * duplicates, and the test asserts against the IRS's own books.
 *
 * <h2>Failure injection is a pure function</h2>
 *
 * <p>{@code roll = sha256(seed | idempotencyKey | attemptNo)}. Failure is therefore
 * reproducible and <em>targetable</em>: a test can arrange, exactly, "this batch fails mode B
 * on attempt 1 and succeeds on attempt 2". A global {@code Random} would give flaky tests and
 * un-debuggable failures; this gives a fixture, and a failing chaos run reproduces from its
 * logged seed.
 *
 * <h2>Mode B is implemented in the order a real system would fail</h2>
 *
 * <p>Record atomically <em>first</em>, then decide whether to lie about it &mdash; because
 * that is what a real endpoint with an idempotency store does: it persists the effect, and
 * then the response path fails. Recording after the roll would produce a stub that could
 * never actually exhibit the behaviour the brief calls the most important line in the
 * document.
 *
 * <p>Note also that <b>mode A throws {@code Indeterminate}, not {@code NotDispatched}</b>.
 * The stub knows nothing was recorded, but a real endpoint could not credibly tell a client
 * that &mdash; and a client that believed such a claim would be exactly wrong under mode B.
 * Making the two indistinguishable at the interface is more faithful, and it means the
 * recovery path is exercised by 12% of calls rather than 5%.
 */
@Service
@ConditionalOnProperty(name = "irs.client", havingValue = "stub", matchIfMissing = true)
@ConditionalOnDatabase
public class StubIrsClient implements IrsTransmissionClient {

    private static final Logger log = LoggerFactory.getLogger(StubIrsClient.class);

    private final StubStore store;
    private final StubProperties config;
    private final AtomicInteger callCounter = new AtomicInteger();

    public StubIrsClient(StubStore store, StubProperties config) {
        this.store = store;
        this.config = config;
    }

    // =================================================================================
    // Submission
    // =================================================================================

    @Override
    public Receipt submit(SubmitRequest request) {
        int callNumber = callCounter.incrementAndGet();

        store.logCall(request.firmId(), "SUBMIT", request.idempotencyKey());
        enforceRateLimit(request.firmId());
        maybeHang(callNumber);
        sleep(sample(config.latency().min(), config.latency().max(), request.idempotencyKey(), "lat"));

        // Server-side idempotency: a retry under the same key replays the original receipt
        // rather than recording anything a second time. This is what makes retrying safe --
        // and the reason the design does NOT depend on it is that a real endpoint might not
        // offer it, which STATUS_FIRST reconciliation exists to survive.
        if (config.idempotentReplay()) {
            Map<String, Object> existing = store.findSubmission(request.idempotencyKey());
            if (existing != null) {
                String outcome = (String) existing.get("outcome_returned");
                if ("MODE_B_ERROR".equals(outcome)) {
                    // Faithful replay of the original lie: the caller is told the same thing
                    // it was told last time. A stub that "came clean" on retry would make
                    // mode B trivially recoverable and prove nothing.
                    throw new TransmissionExceptions.Indeterminate(
                            "replay of a submission that errored after recording (mode B)");
                }
                return new Receipt((String) existing.get("receipt_id"),
                        ((java.sql.Timestamp) existing.get("recorded_at")).toInstant());
            }
        }

        double roll = deterministicRoll(request.idempotencyKey(), callNumber);

        // Mode A: nothing is recorded. Reported as Indeterminate anyway -- see the class note.
        if (roll < config.failureModeARate()) {
            log.debug("stub: mode A on key={} (roll={})", request.idempotencyKey(), roll);
            throw new TransmissionExceptions.Indeterminate(
                    "upstream failure before intake (mode A)");
        }

        boolean modeB = roll < config.failureModeARate() + config.failureModeBRate();

        String receiptId = IrsTransmissionClient.newReceiptId(UUID.randomUUID());
        Duration ackDelay = sample(config.ackDelay().min(), config.ackDelay().max(),
                request.idempotencyKey(), "ack");
        boolean neverAcks = deterministicRoll(request.idempotencyKey(), 9_999) < config.ackNeverRate();
        store.recordSubmission(request, receiptId, modeB, ackDelay, neverAcks,
                filing -> {
                    Rejection rejection = config.rejectRules() ? validate(filing) : null;
                    return rejection == null ? null : new String[]{rejection.code(), rejection.text()};
                });

        if (modeB) {
            log.debug("stub: mode B on key={} -- {} filings ARE live, caller gets an error",
                    request.idempotencyKey(), request.filings().size());
            throw new TransmissionExceptions.Indeterminate(
                    "upstream failure after intake (mode B)");
        }

        return new Receipt(receiptId, Instant.now());
    }

    // =================================================================================
    // Status
    // =================================================================================

    @Override
    public StatusResult status(StatusQuery query) {
        int callNumber = callCounter.incrementAndGet();

        store.logCall(query.firmId(), "STATUS", query.idempotencyKey());
        enforceRateLimit(query.firmId());
        sleep(sample(config.latency().min(), config.latency().max(), query.idempotencyKey(), "lat"));

        Map<String, Object> submission = store.findSubmission(query.idempotencyKey());
        if (submission == null) {
            // POSITIVE PROOF OF NON-DELIVERY. The only answer that permits the caller to
            // release filings and mint a new key. Everything else is "maybe".
            return new StatusResult.Unknown();
        }

        String receiptId = (String) submission.get("receipt_id");

        if (Boolean.TRUE.equals(submission.get("never_acks"))) {
            // "occasionally never", from the brief. The caller's design must not care.
            return new StatusResult.Pending(receiptId);
        }

        // Decided by the database, on the clock that wrote the timestamp -- never by
        // comparing against Instant.now() here. See the note in StubStore.findSubmission.
        if (!Boolean.TRUE.equals(submission.get("acks_ready"))) {
            return new StatusResult.Pending(receiptId);
        }

        List<FilingAck> acks = store.findAcks(query.idempotencyKey()).stream()
                .map(row -> new FilingAck(
                        (String) row.get("client_reference"),
                        "ACCEPTED".equals(row.get("ack")),
                        (String) row.get("irs_record_id"),
                        (String) row.get("reason_code"),
                        (String) row.get("reason_text")))
                .toList();

        return new StatusResult.Resolved(receiptId, acks);
    }

    // =================================================================================
    // Validation, matching the brief's stated rejection reasons
    // =================================================================================

    private record Rejection(String code, String text) {
    }

    private Rejection validate(FilingPayload filing) {
        String tin = filing.recipientTin();
        if (tin == null || tin.length() != 9 || !tin.chars().allMatch(Character::isDigit)) {
            return new Rejection("MALFORMED_TIN", "The recipient TIN is not nine digits.");
        }
        if (tin.startsWith("000")) {
            return new Rejection("TIN_BEGINS_000", "The recipient TIN begins 000.");
        }
        if (filing.nonemployeeCompCents() <= 0 && filing.federalWithheldCents() <= 0) {
            return new Rejection("NON_POSITIVE_AMOUNT", "The reported amount is not positive.");
        }
        return null;
    }

    // =================================================================================
    // Mechanics
    // =================================================================================

    /**
     * Failure as a pure function of {@code (seed, key, attempt)}.
     *
     * <p>The single best decision in the stub. It turns "5% of calls fail" from a source of
     * flaky tests into something a test can arrange precisely, and it means a chaos run that
     * fails can be reproduced exactly from its logged seed.
     */
    private double deterministicRoll(String idempotencyKey, int attemptNo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(config.seed()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Integer.toString(attemptNo).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();

            long value = 0;
            for (int i = 0; i < 7; i++) {
                value = (value << 8) | (hash[i] & 0xff);
            }
            return (double) value / (double) (1L << 56);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and always present", e);
        }
    }

    private Duration sample(Duration min, Duration max, String key, String salt) {
        if (min.equals(max)) {
            return min;
        }
        double roll = deterministicRoll(key + ":" + salt, 0);
        long span = max.toMillis() - min.toMillis();
        return Duration.ofMillis(min.toMillis() + (long) (roll * span));
    }

    /**
     * Blocks on a chosen call so a test can kill the process mid-flight.
     *
     * <p>Combined with {@code failure-mode-b-rate=1.0}, this produces the genuinely worst
     * case in the entire brief: the IRS has recorded the filings, and our process dies before
     * it can learn anything at all.
     */
    private void maybeHang(int callNumber) {
        Integer hangOn = config.hangOnCallNumber();
        if (hangOn != null && hangOn == callNumber) {
            log.warn("stub: hanging on call #{} for {} (test hook)", callNumber, config.hangDuration());
            sleep(config.hangDuration());
        }
    }

    /**
     * The stub's own budget check &mdash; an independent oracle.
     *
     * <p>If our limiter is correct this never fires. When it does, the caller gets a
     * {@code RateLimited}, which the dispatcher treats as a bug in us rather than as
     * ordinary backpressure.
     */
    private void enforceRateLimit(long firmId) {
        if (!config.enforceRateLimit()) {
            return;
        }
        long windowMillis = config.rateWindow().toMillis();
        long recent = store.callsInWindow(firmId, windowMillis);
        if (recent > 20) {
            throw new TransmissionExceptions.RateLimited(
                    "rate budget exceeded at the endpoint: " + recent
                    + " calls in the last " + config.rateWindow());
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransmissionExceptions.Indeterminate("interrupted mid-call", e);
        }
    }
}
