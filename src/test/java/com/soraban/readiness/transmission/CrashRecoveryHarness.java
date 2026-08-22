package com.soraban.readiness.transmission;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test support for killing the transmission path at a chosen point.
 *
 * <p>Registered as a {@link Primary} bean so it replaces the no-op production hook without
 * any profile juggling &mdash; the production path calls the same
 * {@code crashHooks.reached(...)} lines either way.
 */
@TestConfiguration
public class CrashRecoveryHarness {

    /**
     * A {@link CrashHooks} that throws at configured points.
     *
     * <p>Mutable and reset between scenarios rather than constructed per test, because the
     * Spring context is shared and a fresh bean per scenario would mean a fresh context per
     * scenario &mdash; which would take longer than the tests themselves.
     */
    public static class ArmedCrashHooks implements CrashHooks {

        private final Set<CrashPoint> armed = EnumSet.noneOf(CrashPoint.class);
        private final AtomicInteger triggerCount = new AtomicInteger();

        /**
         * Fire only on the Nth arrival at the armed point.
         *
         * <p>Needed because a single drain touches most points many times: without it,
         * "crash after dispatch commits" would fire on the very first batch and the test
         * could never reach the interesting case of crashing partway through a run that has
         * already succeeded several times.
         */
        private int fireOnOccurrence = 1;
        private final AtomicInteger occurrences = new AtomicInteger();

        public void arm(CrashPoint point) {
            arm(point, 1);
        }

        public void arm(CrashPoint point, int occurrence) {
            armed.clear();
            armed.add(point);
            this.fireOnOccurrence = occurrence;
            occurrences.set(0);
        }

        public void disarm() {
            armed.clear();
            occurrences.set(0);
        }

        public int triggerCount() {
            return triggerCount.get();
        }

        @Override
        public void reached(CrashPoint point) {
            if (!armed.contains(point)) {
                return;
            }
            if (occurrences.incrementAndGet() != fireOnOccurrence) {
                return;
            }
            triggerCount.incrementAndGet();

            // An Error, not an Exception. The dispatcher is deliberately full of
            // `catch (Exception e)` blocks -- that is how an unrecognised failure becomes
            // Indeterminate rather than killing a worker -- so a simulated crash thrown as
            // an Exception would be caught, classified, and handled. The test would then
            // silently measure the RETRY path while believing it measured the CRASH path,
            // and would pass having proved nothing.
            throw new SimulatedKill(point);
        }
    }

    @Bean
    @Primary
    public ArmedCrashHooks armedCrashHooks() {
        return new ArmedCrashHooks();
    }
}
