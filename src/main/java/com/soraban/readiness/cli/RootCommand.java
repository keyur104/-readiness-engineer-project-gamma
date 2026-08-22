package com.soraban.readiness.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * The CLI surface.
 *
 * <p>Every subcommand except {@code seed} takes a mandatory {@code --firm}, and runs its
 * entire body inside {@code FirmContext.runAs(...)}. There is deliberately no
 * "run for all firms" mode: firm context is what drives Row-Level Security, so a command
 * that spanned firms would have to either run without context (impossible &mdash; the
 * transaction manager rejects it) or switch context mid-run (possible, but it would make
 * the isolation story conditional on a loop being written correctly). The demo runs each
 * command twice instead. That is a deliberate scoping decision, and it is documented.
 *
 * <p>{@code seed} is exempt because it writes files and touches no tenant table.
 */
@Command(
        name = "readiness",
        description = "Batch 1099-NEC preparation and filing for CPA firms.",
        mixinStandardHelpOptions = true,
        subcommands = {
                SeedCommand.class,
                ImportCommand.class,
                VerifyImportCommand.class,
                DetermineCommand.class,
                FileCommand.class,
                ReconcileCommand.class,
                VerifyInvariantsCommand.class,
                BenchCommand.class,
                VerifyAuditCommand.class
                // 'serve' is not listed: it is handled before picocli sees the arguments,
                // because it keeps the context running instead of executing and exiting.
        }
)
@Component
public class RootCommand implements Runnable {

    @Override
    public void run() {
        // No subcommand given: picocli prints usage via the runner's exit handling.
        throw new picocli.CommandLine.ParameterException(
                new picocli.CommandLine(this),
                "Specify a subcommand. Try 'readiness --help'.");
    }
}
