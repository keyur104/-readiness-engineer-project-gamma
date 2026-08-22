package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2 of the required kill-and-resume test: a <b>real operating-system kill</b> of a
 * <b>real child JVM</b>, mid-request, with the IRS having already recorded the filings.
 *
 * <h2>Why the in-JVM crash points are not enough on their own</h2>
 *
 * <p>{@link KillAndResumeIT} throws an {@code Error} at a named point. That proves the
 * transaction boundaries are exactly where I believe they are, cheaply, for every point, on
 * every build &mdash; and it is genuinely the test that will catch a regression.
 *
 * <p>But it leaves a great deal alive that a real crash does not: JVM statics, the
 * connection pool, in-flight sockets, any shutdown hook, and the JDBC driver's own state.
 * A design could depend on any of those and still pass tier 1.
 *
 * <p>{@link Process#destroyForcibly()} maps to {@code TerminateProcess} on Windows and
 * {@code SIGKILL} on POSIX: <b>no shutdown hook runs, no {@code finally} block executes, no
 * graceful drain happens, and the sockets die uncleanly.</b> Correctness must not depend on
 * cooperative shutdown, because the failure this system is really being designed against is
 * a machine losing power at 3 a.m. on February 1 &mdash; not a polite {@code kill -TERM}.
 *
 * <h2>The scenario is deliberately the worst one available</h2>
 *
 * <p>{@code hang-on-call-number} makes the stub block inside a call, and
 * {@code failure-mode-b-rate=1.0} makes it record every filing first. So at the moment the
 * process dies:
 *
 * <ul>
 *   <li>the IRS <b>has</b> the filings;</li>
 *   <li>we never received a receipt and never will;</li>
 *   <li>nothing about the outcome was written;</li>
 *   <li>and the process is gone without running a single line of cleanup.</li>
 * </ul>
 *
 * <p>That is the strictest reading of the brief's "kill a run mid-batch and resume it".
 *
 * <h2>Why the stub's state lives in PostgreSQL</h2>
 *
 * <p>This test is the reason. An in-memory stub would die <em>with</em> the killed process,
 * so every restart would look like a clean slate and mode B would be unobservable &mdash;
 * the test would pass while proving nothing at all.
 */
@TestPropertySource(properties = {
        "irs.stub.ack-delay.min=0ms",
        "irs.stub.ack-delay.max=0ms",
        "irs.stub.latency.min=0ms",
        "irs.stub.latency.max=0ms",
        "irs.poll.initial-delay=1ms",
        "irs.poll.max-interval=20ms",
        "irs.rate.window=500ms",
        // The endpoint must judge against the same window the client is held to.
        // Leaving this at 60s makes the stub report a breach the client never committed.
        "irs.stub.rate-window=500ms"
})
@EnabledIf("bootJarExists")
class RealProcessKillIT extends TransmissionTestBase {

    private static final Path BOOT_JAR = Path.of("target", "readiness-1.0.0.jar");

    /**
     * Skips rather than fails when the jar is absent.
     *
     * <p>{@code mvn verify} builds it before the integration phase, so the normal path always
     * has it. Running this class directly from an IDE does not &mdash; and a test that failed
     * for that reason would be noise pointing at the wrong thing.
     */
    static boolean bootJarExists() {
        return Files.isRegularFile(BOOT_JAR);
    }

    @Autowired BatchPlanner batchPlanner;
    @Autowired ReconciliationService reconciler;
    @Autowired TransmissionWorker worker;
    @Autowired InvariantChecker invariants;

    @Test
    @DisplayName("SIGKILL a real JVM mid-call, with the filings already live at the IRS, "
               + "then resume: zero duplicates, zero leaks")
    void killRealProcessMidFlight_thenResume() throws Exception {
        // Across THREE clients, because batching is per-client: 30 filings for one client is
        // a single batch, one call, and a run that finishes in 159 ms -- far too fast to kill
        // mid-flight, and a much weaker scenario even if it could be.
        //
        // Three batches means the child completes some calls, has them recorded at the IRS,
        // and is then killed inside a later one. Killing a run that had already partly
        // succeeded is the case that actually distinguishes a correct design.
        givenReadyFilings("T-1", 20);
        givenReadyFilings("T-2", 20);
        givenReadyFilings("T-3", 20);
        FirmContext.runAs(firmId, () -> batchPlanner.planAll(TAX_YEAR));

        long batchesBefore = countBatchesInState("SEALED");
        assertThat(batchesBefore)
                .as("three clients must produce at least three batches, so the kill lands "
                    + "after some calls have already been recorded")
                .isGreaterThanOrEqualTo(3);

        Process child = spawnFilingRun();
        try {
            // Wait until the child is genuinely inside the third call. The stub blocks there,
            // which gives a deterministic window to kill it in -- far more reliable than
            // sleeping for a guessed duration and hoping.
            boolean reachedThirdCall = awaitStubCalls(3, Duration.ofSeconds(60));
            assertThat(reachedThirdCall)
                    .as("the child should have reached the hanging call")
                    .isTrue();

            // SIGKILL. No shutdown hook, no finally, no drain.
            child.destroyForcibly();
            assertThat(child.waitFor(30, TimeUnit.SECONDS))
                    .as("the child must actually be dead before recovery begins")
                    .isTrue();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }

        long recordedAtKill = filingsRecordedAtIrs();

        // The whole point of the scenario: the endpoint holds filings that the dead process
        // never got to tell us about.
        assertThat(recordedAtKill)
                .as("the stub recorded filings before the process died")
                .isGreaterThan(0);

        // Recovery, in this JVM, from nothing but committed state.
        FirmContext.runAs(firmId, reconciler::flagAmbiguousBatches);
        reconciler.reconcile(firmId);
        for (int i = 0; i < 4; i++) {
            worker.drain(firmId, 20);
        }

        assertThat(duplicatesAtIrs())
                .as("no filing may be recorded twice, across a real process death")
                .isZero();
        assertThat(leakedFilings())
                .as("nothing is live at the IRS that our system does not know it sent")
                .isZero();

        InvariantChecker.Report report = invariants.check(firmId);
        assertThat(report.failures())
                .as("every production invariant holds after recovering from SIGKILL")
                .isEmpty();
    }

    // =================================================================================
    // Child process
    // =================================================================================

    /**
     * Launches a genuine {@code file} run in a separate JVM.
     *
     * <p>Runs the packaged boot jar rather than an in-process thread, so the thing being
     * killed is the actual application entry point with its own pool, its own statics and its
     * own sockets.
     */
    private Process spawnFilingRun() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable(),
                "-jar", BOOT_JAR.toAbsolutePath().toString(),
                "file",
                "--firm=northstar",
                "--max-calls=50",
                // Point the child at the TEST database; it must not touch the dev one.
                "--spring.profiles.active=test",
                // Block inside the third call so the kill lands mid-request rather than
                // between requests, which would be a far weaker test.
                "--irs.stub.hang-on-call-number=3",
                "--irs.stub.hang-duration=120s",
                // Every submission records and then reports failure, so by the time we kill
                // it the endpoint is certain to be holding filings we know nothing about.
                "--irs.stub.rate-window=500ms",
                "--irs.stub.failure-mode-b-rate=1.0",
                "--irs.stub.failure-mode-a-rate=0.0",
                "--irs.stub.latency.min=0ms",
                "--irs.stub.latency.max=0ms");

        builder.redirectOutput(ProcessBuilder.Redirect.to(new File("target/child-run.log")));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path candidate = Path.of(home, "bin", "java.exe");
        if (Files.isRegularFile(candidate)) {
            return candidate.toString();
        }
        return Path.of(home, "bin", "java").toString();
    }

    /**
     * Polls the stub's own call log until the child has made {@code target} calls.
     *
     * <p>Polling an observable side effect rather than sleeping: the child has to start a
     * Spring context, run migrations, plan filings and begin transmitting, and how long that
     * takes varies enough that any fixed sleep would be either flaky or slow.
     */
    private boolean awaitStubCalls(int target, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Long calls = inSystemTransaction(() -> jdbc.queryForObject(
                    "select count(*) from irs_stub.call_log", Long.class));
            if (calls != null && calls >= target) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
