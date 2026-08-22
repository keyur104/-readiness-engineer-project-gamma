package com.soraban.readiness.cli;

import com.soraban.readiness.determination.DeterminationEngine;
import com.soraban.readiness.determination.RuleSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Determines which vendors require a 1099-NEC.
 *
 * <pre>
 *   java -jar readiness.jar determine --firm=northstar --tax-year=2025
 *   java -jar readiness.jar determine --firm=northstar --full
 * </pre>
 *
 * <p>Defaults to the incremental path, which processes only clients marked dirty by an
 * import. {@code --full} rescans everything and is the correct choice after a rule change
 * or a normalizer version bump &mdash; both alter <em>who is whom</em> among vendors without
 * touching a single ledger row, so nothing would be marked dirty and an incremental run
 * would quietly leave the book on the old rules.
 */
@Component
@Command(
        name = "determine",
        description = "Decide which vendors require a 1099-NEC, with per-payment reasons.",
        mixinStandardHelpOptions = true
)
public class DetermineCommand implements Callable<Integer> {

    @Option(names = "--firm", required = true, description = "Firm slug.")
    String firmSlug;

    @Option(names = "--tax-year", description = "Filing year. Default: ${DEFAULT-VALUE}")
    int taxYear = 2025;

    @Option(names = "--full",
            description = "Rescan every client instead of only those marked dirty. "
                        + "Required after a rule or name-normalizer change.")
    boolean full = false;

    @Option(names = "--threshold-cents",
            description = "Reporting threshold in cents. Default: ${DEFAULT-VALUE} ($600.00).")
    long thresholdCents = 60_000L;

    private final ObjectProvider<DeterminationEngine> engineProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<com.soraban.readiness.audit.AuditService> auditProvider;

    public DetermineCommand(ObjectProvider<DeterminationEngine> engineProvider,
                            ObjectProvider<JdbcTemplate> jdbcProvider,
                            ObjectProvider<com.soraban.readiness.audit.AuditService> auditProvider) {
        this.engineProvider = engineProvider;
        this.jdbcProvider = jdbcProvider;
        this.auditProvider = auditProvider;
    }

    @Override
    public Integer call() {
        DeterminationEngine engine = engineProvider.getIfAvailable();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (engine == null || jdbc == null) {
            System.err.println("determine needs a database, but none is configured for this run.");
            return 3;
        }

        Long firmId = jdbc.query("select id from app.firm where slug = ?",
                rs -> rs.next() ? rs.getLong(1) : null, firmSlug);
        if (firmId == null) {
            System.err.printf("No firm with slug '%s'.%n", firmSlug);
            return 2;
        }

        RuleSet rules = RuleSet.forTaxYear(taxYear, thresholdCents);
        DeterminationEngine.DeterminationResult result = engine.determine(firmId, rules, full);

        System.out.printf("%ndetermination complete  firm=%s  run=%d  mode=%s  ruleset=%s%n",
                firmSlug, result.runId(), result.mode(), rules.hash());
        System.out.printf("  %-22s %,12d%n", "clients scanned", result.clientsScanned());
        System.out.printf("  %-22s %,12d%n", "payments classified", result.paymentsScanned());
        System.out.printf("  %-22s %,12d%n", "vendors resolved", result.vendorsResolved());
        System.out.printf("  %-22s %,12d%n", "forms required", result.formsRequired());
        System.out.printf("  %-22s %,12d%n", "exceptions raised", result.exceptionsRaised());

        System.out.println("\n  phase timings (ms):");
        result.phaseMs().forEach((phase, ms) -> System.out.printf("    %-22s %,8d%n", phase, ms));

        var audit = auditProvider.getIfAvailable();
        if (audit != null) {
            final long firm = firmId;
            // The ruleset hash is the part that matters here. "Which rules were in force
            // when this decision was made" is the question a penalty notice in June turns
            // into, and a run id alone does not answer it.
            com.soraban.readiness.security.FirmContext.runAs(firm, () ->
                    audit.record(com.soraban.readiness.audit.AuditService.Event.system(
                            "determination", "DETERMINATION_COMPLETED", "DETERMINATION_RUN",
                            Long.toString(result.runId()),
                            java.util.Map.of("taxYear", taxYear,
                                             "mode", result.mode(),
                                             "rulesetHash", rules.hash(),
                                             "thresholdCents", thresholdCents,
                                             "clientsScanned", result.clientsScanned(),
                                             "vendorsResolved", result.vendorsResolved(),
                                             "formsRequired", result.formsRequired(),
                                             "exceptionsRaised", result.exceptionsRaised(),
                                             "totalMs", result.totalMs()))));
        }

        long sla = 60_000;
        System.out.printf("%n  total %,d ms against a %,d ms budget -> %s%n",
                result.totalMs(), sla, result.totalMs() <= sla ? "OK" : "SLA MISSED");

        return 0;
    }
}
