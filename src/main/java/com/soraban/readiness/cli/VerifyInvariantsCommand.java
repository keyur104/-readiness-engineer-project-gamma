package com.soraban.readiness.cli;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.transmission.InvariantChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Runs the invariant suite against whatever state the database happens to be in.
 *
 * <pre>
 *   java -jar readiness.jar verify-invariants --firm=northstar
 *   java -jar readiness.jar verify-invariants --all-firms --verbose
 * </pre>
 *
 * <h2>Why this is a command and not only a test</h2>
 *
 * <p>{@link InvariantChecker} is production code. The same eight assertions that make the
 * kill-and-resume tests meaningful run after startup reconciliation, at the end of every
 * filing run, and here on demand. That is deliberate: a correctness argument that only holds
 * inside a test fixture is an argument about the fixture.
 *
 * <p>So this is an <b>executable definition of "correct"</b> that works in three places a
 * test cannot &mdash; after a demo, against a database someone has been poking at by hand,
 * and in production at 3 a.m. when the question is "did last night's run leave anything
 * broken". It exits non-zero on a violation, so it composes into a cron job or a CI step
 * without anyone having to parse its output.
 *
 * <h2>Why it takes a firm</h2>
 *
 * <p>Every assertion is a firm-scoped query, because every table it reads is under
 * row-level security. There is no context-free "check everything" mode: a transaction with
 * no firm context is rejected outright by {@code FirmTransactionManager}. {@code --all-firms}
 * therefore loops, running each firm's suite in its own context &mdash; which is the honest
 * shape, not a workaround.
 */
@Component
@Command(
        name = "verify-invariants",
        description = "Assert the transmission invariants against the current database state.",
        mixinStandardHelpOptions = true
)
public class VerifyInvariantsCommand implements Callable<Integer> {

    @Option(names = "--firm", description = "Firm slug. Omit with --all-firms.")
    String firmSlug;

    @Option(names = "--all-firms", description = "Check every firm in turn, each in its own context.")
    boolean allFirms = false;

    @Option(names = "--verbose", description = "Print the offending rows for each violation.")
    boolean verbose = false;

    @Option(names = "--max-rows",
            description = "Rows printed per violation with --verbose. Default: ${DEFAULT-VALUE}")
    int maxRows = 10;

    private final ObjectProvider<InvariantChecker> checkerProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public VerifyInvariantsCommand(ObjectProvider<InvariantChecker> checkerProvider,
                                   ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.checkerProvider = checkerProvider;
        this.jdbcProvider = jdbcProvider;
    }

    @Override
    public Integer call() {
        InvariantChecker checker = checkerProvider.getIfAvailable();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (checker == null || jdbc == null) {
            System.err.println("verify-invariants needs a database, but none is configured.");
            return 3;
        }

        if (firmSlug == null && !allFirms) {
            System.err.println("Specify --firm=<slug> or --all-firms.");
            return 2;
        }

        List<Map<String, Object>> firms = allFirms
                ? jdbc.queryForList("select id, slug from app.firm order by id")
                : jdbc.queryForList("select id, slug from app.firm where slug = ?", firmSlug);

        if (firms.isEmpty()) {
            System.err.printf("No firm with slug '%s'.%n", firmSlug);
            return 2;
        }

        boolean allHold = true;

        for (Map<String, Object> firm : firms) {
            long firmId = ((Number) firm.get("id")).longValue();
            String slug = (String) firm.get("slug");

            InvariantChecker.Report report = FirmContext.runAs(firmId, () -> checker.check(firmId));
            allHold &= report.allHold();

            System.out.printf("%nfirm %s (id=%d)  %s%n",
                    slug, firmId, report.allHold() ? "ALL HOLD" : "VIOLATED");

            for (InvariantChecker.Invariant invariant : report.invariants()) {
                System.out.printf("  [%s] %-4s %s%s%n",
                        invariant.holds() ? "PASS" : "FAIL",
                        invariant.id(), invariant.name(),
                        invariant.holds() ? "" : "  (" + invariant.violations().size() + " rows)");

                // The rows themselves, not just a count. An invariant that fails and tells
                // you only that it failed sends you back to psql to write the query again --
                // and the query is right here.
                if (verbose && !invariant.holds()) {
                    invariant.violations().stream().limit(maxRows)
                            .forEach(row -> System.out.printf("         %s%n", row));
                    int hidden = invariant.violations().size() - maxRows;
                    if (hidden > 0) {
                        System.out.printf("         ... and %,d more (raise --max-rows)%n", hidden);
                    }
                }
            }
        }

        System.out.printf("%n%s%n", allHold
                ? "all invariants hold"
                : "INVARIANT VIOLATION -- do not run another filing run until this is understood");

        // Exit status is the product here. A violation means a filing may have been
        // duplicated or lost, which is not a thing to discover by reading scrollback.
        return allHold ? 0 : 1;
    }
}
