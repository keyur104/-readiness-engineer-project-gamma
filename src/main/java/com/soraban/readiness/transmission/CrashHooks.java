package com.soraban.readiness.transmission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Named points at which a test can kill the transmission path.
 *
 * <p>Production code calls {@code crashHooks.reached(AFTER_DISPATCH_COMMIT_BEFORE_CALL)} at
 * each of these; the production implementation does nothing. A test swaps in one that throws.
 *
 * <h2>Why the test implementation must throw an {@link Error}, not an Exception</h2>
 *
 * <p>The dispatcher is full of {@code catch (Exception e)} blocks, and it has to be &mdash;
 * that is how an unrecognised failure becomes {@code Indeterminate} rather than crashing a
 * worker. But it means a simulated crash thrown as an {@code Exception} would be
 * <b>caught, classified, and handled</b>, and the test would quietly measure the retry path
 * instead of the crash path. It would pass, and prove nothing.
 *
 * <p>Throwing an {@code Error} escapes every one of those handlers. And if it turns out
 * something <em>does</em> swallow it, that is a bug worth finding on its own: a
 * {@code catch (Throwable)} in a retry wrapper would convert real JVM errors into silent
 * retries in production too.
 *
 * <h2>What this tier does and does not prove</h2>
 *
 * <p>In-JVM crash points prove that transaction boundaries are exactly where they are
 * believed to be, cheaply and for every point, on every build. They do <b>not</b> prove
 * correctness under a real process death: statics survive, the connection pool survives,
 * in-flight sockets survive, and any shutdown hook still runs.
 *
 * <p>That is why the required test has a second tier that spawns a real JVM and kills it
 * with {@code destroyForcibly()}. These two are complements, not alternatives &mdash; the
 * cheap one gives coverage, the expensive one gives credibility.
 */
public interface CrashHooks {

    /** Points at which the durable state must already be correct. */
    enum CrashPoint {

        /** Membership chosen, nothing committed. Recovery: re-plan from scratch. */
        BEFORE_SEAL_COMMIT,

        /** Batch is SEALED. Provably nothing sent; recovery dispatches normally. */
        AFTER_SEAL_COMMIT,

        /** Intent written but not committed. Rolls back to SEALED -- and the rate token
         *  rolls back with it, which is the payoff of a transactional limiter. */
        BEFORE_DISPATCH_COMMIT,

        /**
         * The write-ahead barrier has committed and no request has gone out.
         *
         * <p>The most instructive point in the set: the batch is DISPATCHED and its filings
         * are SUBMITTED_UNACKNOWLEDGED, yet the IRS has nothing. Recovery must re-dispatch
         * <em>under the same key</em> rather than concluding "nothing happened".
         */
        AFTER_DISPATCH_COMMIT_BEFORE_CALL,

        /** Mid-request. Combined with a forced mode B, this is the genuinely worst case:
         *  the IRS has the filings and we die before learning anything at all. */
        DURING_CALL,

        /** The call returned but the outcome was never persisted. */
        AFTER_CALL_BEFORE_OUTCOME_COMMIT,

        /** A receipt is held; polling has not begun. */
        AFTER_RECEIPT_BEFORE_POLL,

        /** Part-way through applying acknowledgments. */
        DURING_ACK_APPLY
    }

    /** Called at each named point. The production implementation returns immediately. */
    void reached(CrashPoint point);

    /**
     * Thrown by the test implementation. An {@code Error} on purpose &mdash; see the class
     * note. Nothing in the production path may catch this.
     */
    class SimulatedKill extends Error {
        public SimulatedKill(CrashPoint point) {
            super("simulated crash at " + point);
        }
    }

    @Configuration(proxyBeanMethods = false)
    class NoOpConfiguration {

        /**
         * The production hook: an empty method the JIT removes entirely.
         *
         * <p>{@code @ConditionalOnMissingBean} so a test can replace it simply by defining
         * its own, with no profile juggling.
         */
        @Bean
        @ConditionalOnMissingBean(CrashHooks.class)
        public CrashHooks noOpCrashHooks() {
            return point -> {
            };
        }
    }
}
