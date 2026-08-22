package com.soraban.readiness.cli;

import com.soraban.readiness.ingest.ImportPipeline;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Imports one firm's export directory.
 *
 * <pre>
 *   java -jar readiness.jar import --firm=northstar --dir=./data/firm-northstar
 * </pre>
 *
 * <p>{@code --firm} takes a slug and is mandatory. There is deliberately no
 * "import every firm" mode: firm context is what drives Row-Level Security, so a command
 * spanning firms would have to switch context mid-run, making the isolation guarantee
 * conditional on a loop being written correctly rather than on the transaction boundary.
 * The demo runs the command twice.
 */
@Component
@Command(
        name = "import",
        description = "Import a firm's CSV export directory into the ledger.",
        mixinStandardHelpOptions = true
)
public class ImportCommand implements Callable<Integer> {

    @Option(names = "--firm", required = true, description = "Firm slug, e.g. northstar.")
    String firmSlug;

    @Option(names = "--dir", required = true, description = "Export directory containing manifest.json.")
    Path dir;

    /**
     * Injected lazily via {@link ObjectProvider} rather than directly.
     *
     * <p>picocli must be able to construct every registered subcommand in order to render
     * {@code --help}, but the {@code seed} command deliberately boots without a DataSource
     * (it writes files and reads nothing). A hard dependency here would make the whole
     * context fail for {@code seed}, so the requirement is deferred to {@link #call()},
     * where a missing database produces an explanatory message instead of a wall of
     * Spring wiring errors.
     */
    private final ObjectProvider<ImportPipeline> pipelineProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<com.soraban.readiness.audit.AuditService> auditProvider;

    private ImportPipeline pipeline;
    private JdbcTemplate jdbc;

    public ImportCommand(ObjectProvider<ImportPipeline> pipelineProvider,
                         ObjectProvider<JdbcTemplate> jdbcProvider,
                         ObjectProvider<com.soraban.readiness.audit.AuditService> auditProvider) {
        this.pipelineProvider = pipelineProvider;
        this.jdbcProvider = jdbcProvider;
        this.auditProvider = auditProvider;
    }

    @Override
    public Integer call() throws Exception {
        pipeline = pipelineProvider.getIfAvailable();
        jdbc = jdbcProvider.getIfAvailable();
        if (pipeline == null || jdbc == null) {
            System.err.println("import needs a database, but none is configured for this run.");
            return 3;
        }

        Long firmId = resolveFirmId(firmSlug);
        if (firmId == null) {
            System.err.printf("No firm with slug '%s'. Known firms: %s%n",
                    firmSlug, String.join(", ", knownSlugs()));
            return 2;
        }

        ImportPipeline.ImportResult result = pipeline.importExport(firmId, dir);

        System.out.printf("%nimport complete  firm=%s  run=%d%n", firmSlug, result.runId());
        System.out.printf("  %-22s %,12d%n", "rows read", result.rowsRead());
        System.out.printf("  %-22s %,12d%n", "inserted", result.rowsInserted());
        System.out.printf("  %-22s %,12d%n", "updated", result.rowsUpdated());
        System.out.printf("  %-22s %,12d%n", "unchanged", result.rowsUnchanged());
        System.out.printf("  %-22s %,12d%n", "tombstoned", result.rowsTombstoned());
        System.out.printf("  %-22s %,12d%n", "duplicate keys collapsed", result.duplicateKeys());
        System.out.printf("  %-22s %,12d%n", "rejected", result.rowsRejected());
        System.out.printf("  %-22s %,12d%n", "clients marked dirty", result.dirtyClients());

        if (!result.rejectionsByReason().isEmpty()) {
            System.out.println("\n  rejections by reason:");
            result.rejectionsByReason().entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("    %-26s %,8d%n", e.getKey(), e.getValue()));
        }

        System.out.println("\n  phase timings (ms):");
        result.phaseMs().forEach((phase, ms) -> System.out.printf("    %-22s %,8d%n", phase, ms));

        var audit = auditProvider.getIfAvailable();
        if (audit != null) {
            final long firm = firmId;
            com.soraban.readiness.security.FirmContext.runAs(firm, () ->
                    audit.record(com.soraban.readiness.audit.AuditService.Event.system(
                            "import", "IMPORT_COMPLETED", "IMPORT_RUN",
                            Long.toString(result.runId()),
                            Map.of("dir", dir.toString(),
                                   "rowsRead", result.rowsRead(),
                                   "inserted", result.rowsInserted(),
                                   "updated", result.rowsUpdated(),
                                   "tombstoned", result.rowsTombstoned(),
                                   "rejected", result.rowsRejected(),
                                   "dirtyClients", result.dirtyClients(),
                                   "totalMs", result.totalMs()))));
        }

        // The SLA is reported, not merely met quietly. `bench` turns this into a non-zero
        // exit; here it is informational so a partial import still reports its numbers.
        long sla = 120_000;
        System.out.printf("%n  total %,d ms against a %,d ms budget -> %s%n",
                result.totalMs(), sla, result.totalMs() <= sla ? "OK" : "SLA MISSED");

        return 0;
    }

    private Long resolveFirmId(String slug) {
        return jdbc.query("select id from app.firm where slug = ?",
                rs -> rs.next() ? rs.getLong(1) : null, slug);
    }

    private java.util.List<String> knownSlugs() {
        return jdbc.queryForList("select slug from app.firm order by slug", String.class);
    }
}
