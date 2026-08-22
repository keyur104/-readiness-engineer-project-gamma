package com.soraban.readiness.cli;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.transmission.InvariantChecker;
import com.soraban.readiness.transmission.ReconciliationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Settles every batch whose fate is unknown, and does it before anything else may run.
 *
 * <pre>
 *   java -jar readiness.jar reconcile --firm=northstar
 *   java -jar readiness.jar reconcile --firm=northstar --dry-run
 * </pre>
 *
 * <h2>What "unknown" means here</h2>
 *
 * <p>A batch in {@code DISPATCHED} is one where the write-ahead barrier committed and then
 * something stopped: a crash, a timeout, a power cut, or failure mode B &mdash; the IRS
 * recording every filing and then returning an error. Those four look <b>identical</b> in
 * durable state, and that is the design working rather than a gap in it. The recovery is the
 * same for all of them, so they collapse into one epistemic state with one procedure.
 *
 * <p>The procedure never guesses. A batch leaves {@code DISPATCHED} only on evidence from
 * the endpoint: a receipt resolves it forward, and an explicit "never seen this key" resolves
 * it backward by voiding the batch and bumping the filings' attempt epoch. An error, a
 * timeout, or a restart is not evidence and moves nothing.
 *
 * <h2>Why it exists as its own command</h2>
 *
 * <p>{@code file} already reconciles before it plans, so the normal path needs this only as
 * a component. It is separable because reconciliation is the thing an operator wants to run
 * <b>alone</b>: after a crash, the first question is "what is outstanding", and the answer
 * should not require also starting a filing run that begins spending the rate budget on new
 * submissions. Under a 20-call minute, "settle the ambiguous batches and stop" is a real
 * operational need.
 *
 * <p>{@code --dry-run} answers the question without consuming a single call.
 */
@Component
@Command(
        name = "reconcile",
        description = "Settle batches whose outcome is unknown, before any new work is sent.",
        mixinStandardHelpOptions = true
)
public class ReconcileCommand implements Callable<Integer> {

    @Option(names = "--firm", required = true, description = "Firm slug.")
    String firmSlug;

    @Option(names = "--dry-run",
            description = "Report what is outstanding without making a single API call.")
    boolean dryRun = false;

    @Option(names = "--sweep-orphans",
            description = "Also detach batch memberships left behind by a voided batch.")
    boolean sweepOrphans = true;

    private final ObjectProvider<ReconciliationService> reconcilerProvider;
    private final ObjectProvider<InvariantChecker> checkerProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txProvider;

    public ReconcileCommand(ObjectProvider<ReconciliationService> reconcilerProvider,
                            ObjectProvider<InvariantChecker> checkerProvider,
                            ObjectProvider<JdbcTemplate> jdbcProvider,
                            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txProvider) {
        this.reconcilerProvider = reconcilerProvider;
        this.checkerProvider = checkerProvider;
        this.jdbcProvider = jdbcProvider;
        this.txProvider = txProvider;
    }

    @Override
    public Integer call() {
        ReconciliationService reconciler = reconcilerProvider.getIfAvailable();
        InvariantChecker checker = checkerProvider.getIfAvailable();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (reconciler == null || checker == null || jdbc == null) {
            System.err.println("reconcile needs a database, but none is configured.");
            return 3;
        }

        Long firmId = jdbc.query("select id from app.firm where slug = ?",
                rs -> rs.next() ? rs.getLong(1) : null, firmSlug);
        if (firmId == null) {
            System.err.printf("No firm with slug '%s'.%n", firmSlug);
            return 2;
        }
        final long firm = firmId;

        printOutstanding(jdbc, firm);

        if (dryRun) {
            System.out.println("\n--dry-run: nothing was sent and no state changed.");
            // Zero when there is nothing outstanding, so a monitoring job can use this as a
            // "is anything unsettled" probe without a second command.
            return outstandingBatches(jdbc, firm) == 0 ? 0 : 1;
        }

        int flagged = FirmContext.runAs(firm, reconciler::flagAmbiguousBatches);
        ReconciliationService.ReconcileResult result = reconciler.reconcile(firm);

        System.out.printf("%nflagged     %d batch(es) as needing reconciliation%n", flagged);
        System.out.printf("resolved    %d batch(es) on evidence from the endpoint%n", result.resolved());

        int orphans = result.orphans();
        if (sweepOrphans) {
            orphans += FirmContext.runAs(firm, reconciler::sweepOrphans);
        }
        System.out.printf("orphans     %d membership(s) detached%n", orphans);

        boolean stillPending = FirmContext.runAs(firm, reconciler::pending);
        if (stillPending) {
            // Not a failure. A batch the endpoint has not yet answered about stays flagged
            // and stays scheduled; the point of saying so is that the planner is gated until
            // it clears, so an operator wondering why nothing new is going out has the reason.
            System.out.println("\nSome batches remain unsettled. They are still scheduled and will be "
                             + "retried; new submissions for this firm stay gated until they resolve.");
        }

        InvariantChecker.Report report = FirmContext.runAs(firm, () -> checker.check(firm));
        System.out.printf("%ninvariants  %s%n", report.allHold() ? "ALL HOLD" : "VIOLATED");
        report.failures().forEach(invariant ->
                System.out.printf("  [FAIL] %-4s %s  (%d rows)%n",
                        invariant.id(), invariant.name(), invariant.violations().size()));

        return report.allHold() ? 0 : 1;
    }

    /**
     * What is outstanding, read in one transaction so the three counts agree with each other.
     */
    private void printOutstanding(JdbcTemplate jdbc, long firmId) {
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setName("cli:reconcile-survey");
        definition.setReadOnly(true);
        var template = new org.springframework.transaction.support.TransactionTemplate(
                txProvider.getObject(), definition);

        FirmContext.runAs(firmId, () -> template.execute(status -> {
            System.out.printf("firm %s -- outstanding work%n%n", firmSlug);

            jdbc.queryForList("""
                    select state,
                           count(*)                                as batches,
                           sum(filing_count)                       as filings,
                           count(*) filter (where needs_reconcile) as flagged,
                           min(to_char(first_dispatch_at, 'DD Mon HH24:MI:SS')) as oldest_dispatch
                      from app.filing_batch
                     where state in ('SEALED', 'DISPATCHED', 'SUBMITTED')
                     group by state
                     order by state
                    """).forEach(row -> System.out.printf(
                            "  %-12s batches=%-5s filings=%-7s flagged=%-5s oldest=%s%n",
                            row.get("state"), row.get("batches"), row.get("filings"),
                            row.get("flagged"), row.get("oldest_dispatch")));

            // DISPATCHED is the interesting one, and it is worth naming rather than leaving
            // the reader to infer it from a state name.
            Long ambiguous = jdbc.queryForObject(
                    "select count(*) from app.filing_batch where state = 'DISPATCHED'", Long.class);
            if (ambiguous != null && ambiguous > 0) {
                System.out.printf("%n  %d batch(es) are DISPATCHED: we committed the intent to send them "
                                + "and never learned the outcome.%n"
                                + "  Whether the IRS holds those filings is unknown, and a crash, a "
                                + "timeout and failure mode B%n"
                                + "  are indistinguishable here. Only a status call settles it.%n",
                        ambiguous);
            }
            return null;
        }));
    }

    private long outstandingBatches(JdbcTemplate jdbc, long firmId) {
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setName("cli:reconcile-count");
        definition.setReadOnly(true);
        var template = new org.springframework.transaction.support.TransactionTemplate(
                txProvider.getObject(), definition);

        Long count = FirmContext.runAs(firmId, () -> template.execute(status -> jdbc.queryForObject(
                "select count(*) from app.filing_batch where state = 'DISPATCHED'", Long.class)));
        return count == null ? 0 : count;
    }
}
