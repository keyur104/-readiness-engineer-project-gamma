package com.soraban.readiness.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Bridges Spring Boot's startup into picocli.
 *
 * <p>Uses picocli's Spring {@link IFactory} so subcommands are resolved as Spring beans and
 * get constructor injection &mdash; which is what lets {@code ImportCommand} take a
 * {@code JdbcTemplate} while {@code SeedCommand} takes nothing at all.
 *
 * <p>Implements {@link ExitCodeGenerator} so a command's return value becomes the process
 * exit status. That matters for {@code bench}, which is required to exit non-zero when a
 * stated SLA is missed &mdash; making the performance claim something a reviewer can verify
 * with {@code echo $?} rather than something they have to read and believe.
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final RootCommand rootCommand;
    private final IFactory factory;
    private int exitCode;

    public CliRunner(RootCommand rootCommand, IFactory factory) {
        this.rootCommand = rootCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || isServe(args)) {
            // 'serve' keeps the web context and the workers running; there is nothing for
            // picocli to execute and nothing to exit from.
            return;
        }
        exitCode = new CommandLine(rootCommand, factory).execute(withoutSpringProperties(args));
    }

    /**
     * Strips Spring property overrides before handing the rest to picocli.
     *
     * <p>Spring Boot reads {@code --some.property=value} arguments into the Environment, but
     * they stay in the array that reaches the command runner &mdash; and picocli, which knows
     * nothing about them, rejects the whole invocation as "Unknown options".
     *
     * <p>That would make a perfectly reasonable command line impossible:
     * <pre>
     *   java -jar readiness.jar file --firm=northstar --spring.profiles.active=test
     * </pre>
     * which is exactly how the kill-and-resume test points a child process at the test
     * database, and how anyone would override a stub setting for a demo.
     *
     * <p>The discriminator is a dot in the option name. Every picocli option here is
     * kebab-case ({@code --tax-year}, {@code --max-calls}), and Spring property keys are
     * dotted ({@code --irs.stub.hang-on-call-number}), so the two namespaces do not overlap.
     */
    static String[] withoutSpringProperties(String[] args) {
        return java.util.Arrays.stream(args)
                .filter(arg -> !SPRING_PROPERTY.matcher(arg).matches())
                .toArray(String[]::new);
    }

    private static final java.util.regex.Pattern SPRING_PROPERTY =
            java.util.regex.Pattern.compile("^--[A-Za-z0-9_]+([.][A-Za-z0-9_.-]+)+=.*$");

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** True when the process should keep running rather than execute a command and exit. */
    public static boolean isServe(String[] args) {
        return args.length > 0 && "serve".equals(args[0]);
    }
}
