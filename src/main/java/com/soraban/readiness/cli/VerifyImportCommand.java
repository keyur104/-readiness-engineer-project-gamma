package com.soraban.readiness.cli;

import com.soraban.readiness.ingest.BookChecksum;
import com.soraban.readiness.ingest.ImportPipeline;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Proves that re-importing an unchanged export changes nothing.
 *
 * <pre>
 *   java -jar readiness.jar verify-import --firm=northstar --dir=./data/firm-northstar
 * </pre>
 *
 * <p>Takes a snapshot, re-imports the same directory, takes another, and compares. Exits
 * non-zero if anything moved, so the claim is something a reviewer can check with
 * {@code echo $?} rather than something they have to read and believe.
 *
 * <h2>Why it re-imports rather than short-circuiting on the file checksum</h2>
 *
 * <p>The importer records each file's SHA-256 and could simply skip a file it has already
 * seen. It deliberately does not. Skipping proves only that the file is unchanged; running
 * the import and proving the merge was a no-op proves the property that actually matters --
 * that the merge itself is idempotent. The cheap version would pass even if the merge were
 * badly broken.
 */
@Component
@Command(
        name = "verify-import",
        description = "Re-import an unchanged export and prove nothing changed.",
        mixinStandardHelpOptions = true
)
public class VerifyImportCommand implements Callable<Integer> {

    @Option(names = "--firm", required = true, description = "Firm slug.")
    String firmSlug;

    @Option(names = "--dir", required = true, description = "Export directory to re-import.")
    Path dir;

    private final ObjectProvider<ImportPipeline> pipelineProvider;
    private final ObjectProvider<BookChecksum> checksumProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public VerifyImportCommand(ObjectProvider<ImportPipeline> pipelineProvider,
                               ObjectProvider<BookChecksum> checksumProvider,
                               ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.pipelineProvider = pipelineProvider;
        this.checksumProvider = checksumProvider;
        this.jdbcProvider = jdbcProvider;
    }

    @Override
    public Integer call() throws Exception {
        ImportPipeline pipeline = pipelineProvider.getIfAvailable();
        BookChecksum checksums = checksumProvider.getIfAvailable();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (pipeline == null || checksums == null || jdbc == null) {
            System.err.println("verify-import needs a database, but none is configured for this run.");
            return 3;
        }

        Long firmId = jdbc.query("select id from app.firm where slug = ?",
                rs -> rs.next() ? rs.getLong(1) : null, firmSlug);
        if (firmId == null) {
            System.err.printf("No firm with slug '%s'.%n", firmSlug);
            return 2;
        }

        // Firm context is established OUTSIDE each transactional call, because
        // FirmTransactionManager reads it at transaction start -- see BookChecksum.snapshot().
        //
        // Dirty marks are cleared first so the "dirty set is empty afterwards" assertion is
        // about THIS import rather than about whatever ran before it.
        final long firm = firmId;
        com.soraban.readiness.security.FirmContext.runAs(firm, checksums::clearDirtyMarks);

        BookChecksum.Snapshot before =
                com.soraban.readiness.security.FirmContext.runAs(firm, checksums::snapshot);
        System.out.printf("before   rows=%,d deleted=%,d vendors=%,d checksum=%s dirty=%d%n",
                before.liveRows(), before.deletedRows(), before.vendorCount(),
                before.checksumHex(), before.dirtyClients());

        ImportPipeline.ImportResult result = pipeline.importExport(firmId, dir);

        BookChecksum.Snapshot after =
                com.soraban.readiness.security.FirmContext.runAs(firm, checksums::snapshot);
        System.out.printf("after    rows=%,d deleted=%,d vendors=%,d checksum=%s dirty=%d%n%n",
                after.liveRows(), after.deletedRows(), after.vendorCount(),
                after.checksumHex(), after.dirtyClients());

        record Check(String name, boolean passed, String detail) {
        }

        var checks = java.util.List.of(
                new Check("no rows inserted", result.rowsInserted() == 0,
                        "inserted=" + result.rowsInserted()),
                new Check("no rows updated", result.rowsUpdated() == 0,
                        "updated=" + result.rowsUpdated()),
                new Check("no rows tombstoned", result.rowsTombstoned() == 0,
                        "tombstoned=" + result.rowsTombstoned()),
                new Check("live row count unchanged", before.liveRows() == after.liveRows(),
                        before.liveRows() + " -> " + after.liveRows()),
                new Check("book checksum unchanged", before.checksum() == after.checksum(),
                        before.checksumHex() + " -> " + after.checksumHex()),
                new Check("vendor count unchanged", before.vendorCount() == after.vendorCount(),
                        before.vendorCount() + " -> " + after.vendorCount()),
                // The strongest of the six: nothing was even CONSIDERED changed, so the
                // downstream cost of a redundant import is provably zero.
                new Check("dirty set still empty", after.dirtyClients() == 0,
                        "dirty=" + after.dirtyClients()));

        boolean allPassed = true;
        for (Check check : checks) {
            System.out.printf("  [%s] %-26s %s%n",
                    check.passed() ? "PASS" : "FAIL", check.name(), check.detail());
            allPassed &= check.passed();
        }

        System.out.printf("%n%s  (%,d rows read, %,d unchanged, %,d ms)%n",
                allPassed ? "IDEMPOTENT" : "NOT IDEMPOTENT",
                result.rowsRead(), result.rowsUnchanged(), result.totalMs());

        return allPassed ? 0 : 1;
    }
}
