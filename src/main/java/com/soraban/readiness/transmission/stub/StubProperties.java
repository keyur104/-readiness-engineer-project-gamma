package com.soraban.readiness.transmission.stub;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Every knob the fake IRS exposes.
 *
 * <p>The brief requires failure rates, latency, and delays to be configurable
 * "including zero, for tests". That is treated here as the organizing principle rather than
 * a checkbox: because everything is configuration, the <b>real</b> limiter, the <b>real</b>
 * backoff policy, and the <b>real</b> reconciliation code run in every test &mdash; just
 * scaled down. Nothing is mocked, so nothing can pass because a mock was wrong.
 *
 * @param seed              makes failure injection a pure function, so a scenario is a
 *                          fixture rather than a coin flip
 * @param failureModeARate  fails before anything is recorded; retrying is safe
 * @param failureModeBRate  records every filing, then returns an error anyway
 * @param latency           artificial round-trip delay
 * @param ackDelay          how long until acknowledgments become visible
 * @param ackNeverRate      share of submissions that never acknowledge at all
 * @param idempotentReplay  whether the endpoint honours idempotency keys; set false to prove
 *                          the system survives one that does not
 * @param enforceRateLimit  whether the stub independently rejects over-budget calls
 * @param rateWindow       the rolling window the budget is measured over. The brief
 *                          specifies 20 calls per rolling 60 seconds, which is the default.
 *                          Configurable only so a test can compress it in step with
 *                          {@code irs.rate.window}: shrinking the client's window while the
 *                          endpoint still judges against a literal minute makes the endpoint
 *                          report a breach the client never committed.
 * @param rejectRules       whether per-filing validation rejections are produced
 * @param hangOnCallNumber  test hook: block on the Nth call, to be killed mid-flight
 * @param hangDuration      how long to block
 */
@ConfigurationProperties("irs.stub")
public record StubProperties(
        long seed,
        double failureModeARate,
        double failureModeBRate,
        Latency latency,
        AckDelay ackDelay,
        double ackNeverRate,
        boolean idempotentReplay,
        boolean enforceRateLimit,
        Duration rateWindow,
        boolean rejectRules,
        Integer hangOnCallNumber,
        Duration hangDuration
) {

    public record Latency(Duration min, Duration max) {
    }

    public record AckDelay(Duration min, Duration max) {
    }

    public StubProperties {
        if (latency == null) {
            latency = new Latency(Duration.ZERO, Duration.ZERO);
        }
        if (ackDelay == null) {
            ackDelay = new AckDelay(Duration.ofSeconds(10), Duration.ofSeconds(30));
        }
        if (hangDuration == null) {
            hangDuration = Duration.ofSeconds(10);
        }
        if (rateWindow == null || rateWindow.isZero() || rateWindow.isNegative()) {
            rateWindow = Duration.ofSeconds(60);
        }
    }

    /** Defaults matching the brief: 7% mode A, 5% mode B, 10&ndash;30 s acknowledgment. */
    public static StubProperties defaults() {
        return new StubProperties(42L, 0.07, 0.05,
                new Latency(Duration.ZERO, Duration.ZERO),
                new AckDelay(Duration.ofSeconds(10), Duration.ofSeconds(30)),
                0.0, true, true, Duration.ofSeconds(60), true, null, Duration.ofSeconds(10));
    }
}
