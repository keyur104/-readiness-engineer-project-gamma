package com.soraban.readiness;

import com.soraban.readiness.cli.CliRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Set;

/**
 * Batch 1099-NEC preparation and filing for CPA firms.
 *
 * <p>Two entry shapes share one Spring context:
 * <ul>
 *   <li>{@code serve} &mdash; the morning-after page plus the per-firm transmission workers.</li>
 *   <li>the CLI subcommands, which run headless and exit with a meaningful status code.</li>
 * </ul>
 *
 * <p>The same handler code backs both, so the demo path and the production path are never
 * two different implementations. That matters for the kill-and-resume demo in particular:
 * {@code file --firm=1} drives exactly the workers {@code serve} would.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    /**
     * Commands that need no database.
     *
     * <p>Only {@code seed} qualifies: it reads nothing and writes CSV files. Booting a
     * DataSource for it would mean a reviewer could not generate a corpus and look at it
     * before setting Postgres up &mdash; and it would fail confusingly at a step that has
     * nothing to do with what they asked for.
     */
    private static final Set<String> DATABASE_FREE_COMMANDS = Set.of("seed");

    /** Anything that is neither a database-free command nor {@code serve} still needs a web-less context. */
    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "";
        boolean serve = CliRunner.isServe(args);

        SpringApplicationBuilder builder = new SpringApplicationBuilder(Application.class)
                .web(serve ? WebApplicationType.SERVLET : WebApplicationType.NONE);

        if (DATABASE_FREE_COMMANDS.contains(command)) {
            builder.properties(
                    // One explicit switch that every database-dependent bean gates on.
                    // Property conditions are evaluated from the Environment, so unlike
                    // @ConditionalOnBean they do not depend on whether component scanning
                    // happens to run before or after auto-configuration.
                    "readiness.database.enabled=false",
                    "spring.autoconfigure.exclude="
                            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                    "spring.flyway.enabled=false");
        }

        // Banner off: the CLI's output is the product here, and a banner in front of a
        // timings table is noise a reviewer has to scroll past.
        builder.bannerMode(org.springframework.boot.Banner.Mode.OFF);

        ConfigurableApplicationContext context = builder.run(args);

        if (!serve) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
