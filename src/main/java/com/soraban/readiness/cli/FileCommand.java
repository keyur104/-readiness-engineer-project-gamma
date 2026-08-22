package com.soraban.readiness.cli;

import com.soraban.readiness.audit.AuditService;
import com.soraban.readiness.transmission.BatchPlanner;
import com.soraban.readiness.transmission.FilingPlanner;
import com.soraban.readiness.transmission.InvariantChecker;
import com.soraban.readiness.transmission.ReconciliationService;
import com.soraban.readiness.transmission.TransmissionWorker;
import com.soraban.readiness.security.FirmContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Runs a filing run: create filings, seal batches, transmit, poll.
 *
 * <pre>
 *   java -jar readiness.jar file --firm=northstar --tax-year=2025
 *   java -jar readiness.jar file --firm=northstar --max-calls=40   # bounded, for a demo
 * </pre>
 *
 * <p><b>The order is not arbitrary.</b> Reconciliation runs first and completes before the
 * planner is allowed to seal anything, because a batch whose fate is unknown must be settled
 * before the scarce rate budget is spent on new work &mdash; and because a planner running
 * against unreconciled state is the one situation where a filing that is genuinely live could
 * look eligible again.
 *
 * <p>This is the same code path {@code serve} runs, so the demo and the real thing are never
 * two different implementations. That matters most for the kill-and-resume demo: what gets
 * killed is the actual worker.
 */
@Component
@Command(
        name = "file",
        description = "Run a filing run: plan filings, seal batches, transmit, and poll.",
        mixinStandardHelpOptions = true
)
public class FileCommand implements Callable<Integer> {

    @Option(names = "--firm", required = true, description = "Firm slug.")
    String firmSlug;

    @Option(names = "--tax-year", description = "Filing year. Default: ${DEFAULT-VALUE}")
    int taxYear = 2025;

    @Option(names = "--max-calls",
            description = "Stop after this many API calls. -1 for unbounded. Default: ${DEFAULT-VALUE}")
    int maxCalls = -1;

    @Option(names = "--plan-only", description = "Create filings and seal batches, but transmit nothing.")
    boolean planOnly = false;

    private final ObjectProvider<FilingPlanner> filingPlannerProvider;
    private final ObjectProvider<BatchPlanner> batchPlannerProvider;
    private final ObjectProvider<TransmissionWorker> workerProvider;
    private final ObjectProvider<ReconciliationService> reconcileProvider;
    private final ObjectProvider<InvariantChecker> invariantProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<AuditService> auditProvider;
    private final ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txProvider;

    public FileCommand(ObjectProvider<FilingPlanner> filingPlannerProvider,
                       ObjectProvider<BatchPlanner> batchPlannerProvider,
                       ObjectProvider<TransmissionWorker> workerProvider,
                       ObjectProvider<ReconciliationService> reconcileProvider,
                       ObjectProvider<InvariantChecker> invariantProvider,
                       ObjectProvider<JdbcTemplate> jdbcProvider,
                       ObjectProvider<AuditService> auditProvider,
                       ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txProvider) {
        this.filingPlannerProvider = filingPlannerProvider;
        this.batchPlannerProvider = batchPlannerProvider;
        this.workerProvider = workerProvider;
        this.reconcileProvider = reconcileProvider;
        this.invariantProvider = invariantProvider;
        this.jdbcProvider = jdbcProvider;
        this.auditProvider = auditProvider;
        this.txProvider = txProvider;
    }

    @Override
    public Integer call() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        FilingPlanner filingPlanner = filingPlannerProvider.getIfAvailable();
        BatchPlanner batchPlanner = batchPlannerProvider.getIfAvailable();
        TransmissionWorker worker = workerProvider.getIfAvailable();
        ReconciliationService reconciler = reconcileProvider.getIfAvailable();
        InvariantChecker invariants = invariantProvider.getIfAvailable();

        if (jdbc == null || filingPlanner == null || batchPlanner == null
                || worker == null || reconciler == null || invariants == null) {
            System.err.println("file needs a database, but none is configured for this run.");
            return 3;
        }

        Long firmId = jdbc.query("select id from app.firm where slug = ?",
                rs -> rs.next() ? rs.getLong(1) : null, firmSlug);
        if (firmId == null) {
            System.err.printf("No firm with slug '%s'.%n", firmSlug);
            return 2;
        }
        final long firm = firmId;

        long startedAt = System.nanoTime();

        // 1. Settle anything ambiguous BEFORE spending budget on new work.
        //
        // Flagging runs inside firm context like everything else. There is deliberately no
        // cross-firm write path: app.filing_batch is firm-scoped, so a context-free
        // transaction would be rejected outright -- which is the isolation design working,
        // not an obstacle to route around. A multi-firm deployment flags each firm in turn.
        int flagged = FirmContext.runAs(firm, reconciler::flagAmbiguousBatches);
        ReconciliationService.ReconcileResult reconciled = reconciler.reconcile(firm);
        System.out.printf("reconcile   flagged=%d acted=%d orphans=%d%n",
                flagged, reconciled.resolved(), reconciled.orphans());

        // 2. Determination -> filings, with preflight.
        FilingPlanner.PlanResult filings =
                FirmContext.runAs(firm, () -> filingPlanner.planFilings(taxYear));
        System.out.printf("filings     created=%d ready=%d blocked=%d frozen=%d%n",
                filings.created(), filings.ready(), filings.blocked(), filings.frozen());

        // 3. Seal batches, one client at a time.
        int sealed = FirmContext.runAs(firm, () -> batchPlanner.planAll(taxYear));
        System.out.printf("batches     sealed=%d%n", sealed);

        int calls = 0;
        if (!planOnly) {
            // 4. Transmit and poll under the shared rate budget.
            calls = worker.drain(firm, maxCalls);
            System.out.printf("transmit    api_calls=%d%n", calls);
        }

        // 5. The same assertions the tests use, run against whatever state we ended in.
        InvariantChecker.Report report = invariants.check(firm);
        System.out.printf("%ninvariants  %s%n", report.allHold() ? "ALL HOLD" : "VIOLATED");
        for (InvariantChecker.Invariant invariant : report.invariants()) {
            System.out.printf("  [%s] %-4s %s%s%n",
                    invariant.holds() ? "PASS" : "FAIL", invariant.id(), invariant.name(),
                    invariant.holds() ? "" : "  (" + invariant.violations().size() + " violations)");
        }

        printSummary(jdbc, firm);

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        System.out.printf("%nfiling run complete in %,d ms (%d API calls)%n", elapsedMs, calls);

        // ONE audit event for the whole run, not one per filing.
        //
        // A run that transmits ten thousand forms is a single decision by a single actor;
        // recording it ten thousand times would bury the human actions this log exists to
        // make findable, and the per-filing history already lives in filing_state_transition
        // and transmission_attempt. Machine work is audited at the run level, human actions
        // per action -- that rule is what keeps this table auditable by hand.
        AuditService audit = auditProvider.getIfAvailable();
        if (audit != null) {
            final int apiCalls = calls;
            FirmContext.runAs(firm, () -> audit.record(AuditService.Event.system(
                    "filing-run", "FILING_RUN_COMPLETED", "FIRM", Long.toString(firm),
                    java.util.Map.of(
                            "taxYear", taxYear,
                            "filingsCreated", filings.created(),
                            "batchesSealed", sealed,
                            "apiCalls", apiCalls,
                            "reconciledBatches", reconciled.resolved(),
                            "elapsedMs", elapsedMs,
                            "invariants", report.allHold() ? "ALL_HOLD" : "VIOLATED",
                            "planOnly", planOnly))));
        }

        // Non-zero on a broken invariant: the claim is checkable with echo $?, not something
        // a reviewer has to read and believe.
        return report.allHold() ? 0 : 1;
    }

    /**
     * Reads the end-state summary in one read-only transaction.
     *
     * <p>A transaction is required, not optional: firm context is applied at transaction
     * start, so a bare query outside one has no {@code app.current_firm_id} and RLS raises
     * 28000. One transaction also means the three summaries are a single consistent snapshot
     * rather than three reads that could disagree during an active run.
     */
    private void printSummary(JdbcTemplate jdbc, long firmId) {
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setName("cli:file-summary");
        definition.setReadOnly(true);
        var template = new org.springframework.transaction.support.TransactionTemplate(
                txProvider.getObject(), definition);

        FirmContext.runAs(firmId, () -> template.execute(status -> {
            System.out.println("\nfiling states:");
            jdbc.queryForList("""
                    select state, count(*) as count from app.filing group by state order by count(*) desc
                    """).forEach(row ->
                    System.out.printf("  %-26s %,8d%n", row.get("state"), row.get("count")));

            System.out.println("\nbatch states:");
            jdbc.queryForList("""
                    select state, count(*) as count from app.filing_batch group by state order by count(*) desc
                    """).forEach(row ->
                    System.out.printf("  %-26s %,8d%n", row.get("state"), row.get("count")));

            var attention = jdbc.queryForList("""
                    select type, severity, count(*) as count from app.attention_item
                     where resolved_at is null group by type, severity order by severity, count(*) desc
                    """);
            if (!attention.isEmpty()) {
                System.out.println("\nneeds a person:");
                attention.forEach(row ->
                        System.out.printf("  [sev %s] %-38s %,8d%n",
                                row.get("severity"), row.get("type"), row.get("count")));
            }
            return null;
        }));
    }
}
