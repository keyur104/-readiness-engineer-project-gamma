package com.soraban.readiness.cli;

import com.soraban.readiness.seed.SeedConfig;
import com.soraban.readiness.seed.SeedGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Generates the seed corpus.
 *
 * <p>The only command that touches no database at all &mdash; it reads nothing and writes
 * CSV files. {@code Application} detects this and skips DataSource, Flyway, and
 * {@code RlsGuard} entirely, so a reviewer can generate a corpus and inspect it before
 * setting Postgres up.
 *
 * <pre>
 *   java -jar readiness.jar seed --seed=42 --clients=500 --lines=1000000 --out=./data
 *   java -jar readiness.jar seed --lines=5000 --clients=20 --out=./data-small   # quick look
 * </pre>
 */
@Command(
        name = "seed",
        description = "Generate a deterministic ledger corpus (CSV exports + manifests).",
        mixinStandardHelpOptions = true
)
@Component
public class SeedCommand implements Callable<Integer> {

    @Option(names = "--seed",
            description = "Root seed. The same value reproduces byte-identical output. Default: ${DEFAULT-VALUE}")
    long seed = 42L;

    @Option(names = "--clients",
            description = "Total business clients across all firms. Default: ${DEFAULT-VALUE}")
    int clients = 500;

    @Option(names = "--lines",
            description = "Approximate total ledger lines. Default: ${DEFAULT-VALUE}")
    long lines = 1_000_000L;

    @Option(names = "--firms",
            split = ",",
            description = "Firm slugs. Default: ${DEFAULT-VALUE}")
    List<String> firms = SeedConfig.DEFAULT_FIRMS;

    @Option(names = "--tax-year", description = "Filing year. Default: ${DEFAULT-VALUE}")
    int taxYear = 2025;

    @Option(names = "--out", description = "Output directory. Default: ${DEFAULT-VALUE}")
    Path out = Path.of("data");

    @Option(names = "--revision",
            description = "0 for the original export; N applies the Nth scripted revision "
                        + "(the bookkeeper found a missed invoice). Default: ${DEFAULT-VALUE}")
    int revision = 0;

    @Option(names = "--gzip", description = "Compress output; the importer sniffs the magic bytes.")
    boolean gzip = false;

    @Override
    public Integer call() throws Exception {
        SeedConfig config = new SeedConfig(seed, firms, clients, lines, taxYear, out, revision, gzip);
        SeedGenerator.SeedResult result = new SeedGenerator(config).generate();

        System.out.printf("seed complete: %,d rows across %d firms in %,d ms -> %s%n",
                result.totalRows(), firms.size(), result.elapsedMs(), result.outputDir().toAbsolutePath());
        result.rowsByFirm().forEach((firm, count) ->
                System.out.printf("  %-14s %,10d rows%n", firm, count));
        System.out.printf("  %-14s %s%n", "fixtures",
                result.outputDir().resolve("fixtures.json").toAbsolutePath());

        return 0;
    }
}
