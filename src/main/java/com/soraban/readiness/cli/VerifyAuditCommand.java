package com.soraban.readiness.cli;

import com.soraban.readiness.audit.AuditService;
import com.soraban.readiness.security.FirmContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Recomputes the audit chain and reports whether it is intact.
 *
 * <pre>
 *   java -jar readiness.jar verify-audit --all-firms
 *   java -jar readiness.jar verify-audit --firm=northstar --pin=audit-heads.txt
 * </pre>
 *
 * <h2>What {@code --pin} is for, and the limitation it admits</h2>
 *
 * <p>A hash chain is tamper-<b>evident</b>, not tamper-<b>proof</b>. Anyone who can write
 * both {@code app.audit_event} and {@code app.audit_chain_head} can rewrite history and
 * recompute the chain over it, and this command would then report it as intact. Saying
 * otherwise would be the kind of security claim that is worse than none, because it stops
 * people asking the next question.
 *
 * <p>What closes the gap is a copy of the head that lives somewhere the application cannot
 * reach. {@code --pin} appends the current head to a file outside the database; comparing
 * today's run against yesterday's pinned line detects a rewrite, because the attacker would
 * have had to alter the file too. In production that file is WORM storage or a transparency
 * log; here it is a local append-only text file, and the difference is a deployment concern
 * this project <em>states</em> rather than pretends to have solved.
 *
 * <p>Exits non-zero on a broken chain, so it composes into a nightly job.
 */
@Component
@Command(
        name = "verify-audit",
        description = "Recompute the audit hash chain and report whether it is intact.",
        mixinStandardHelpOptions = true
)
public class VerifyAuditCommand implements Callable<Integer> {

    @Option(names = "--firm", description = "Firm slug. Omit with --all-firms.")
    String firmSlug;

    @Option(names = "--all-firms", description = "Verify every firm's chain in turn.")
    boolean allFirms = false;

    @Option(names = "--pin",
            description = "Append the verified head to this file, outside the database.")
    Path pin;

    @Option(names = "--tail",
            description = "Also print the most recent N events. Default: ${DEFAULT-VALUE}")
    int tail = 0;

    private final ObjectProvider<AuditService> auditProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txProvider;

    public VerifyAuditCommand(ObjectProvider<AuditService> auditProvider,
                              ObjectProvider<JdbcTemplate> jdbcProvider,
                              ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txProvider) {
        this.auditProvider = auditProvider;
        this.jdbcProvider = jdbcProvider;
        this.txProvider = txProvider;
    }

    @Override
    public Integer call() throws Exception {
        AuditService audit = auditProvider.getIfAvailable();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (audit == null || jdbc == null) {
            System.err.println("verify-audit needs a database, but none is configured.");
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

        boolean allIntact = true;

        for (Map<String, Object> firm : firms) {
            long firmId = ((Number) firm.get("id")).longValue();
            String slug = (String) firm.get("slug");

            AuditService.Verification result = FirmContext.runAs(firmId, () -> audit.verify(firmId));
            allIntact &= result.intact();

            System.out.printf("%nfirm %s (id=%d)%n", slug, firmId);
            System.out.printf("  events     %,d%n", result.events());
            System.out.printf("  chain      %s%n", result.intact() ? "INTACT" : "BROKEN");
            System.out.printf("  head       %s%n", result.headHex());

            result.problems().forEach(problem -> System.out.printf("  [BROKEN]   %s%n", problem));

            if (tail > 0) {
                printTail(jdbc, firmId, tail);
            }

            if (pin != null && result.intact()) {
                // Only a verified head is worth pinning. Recording the head of a chain that
                // does not verify would enshrine the tampered state as the new baseline.
                String line = "%s  firm=%s  events=%d  head=%s%n".formatted(
                        Instant.now(), slug, result.events(), result.headHex());
                Files.writeString(pin, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.printf("  pinned     %s%n", pin.toAbsolutePath());
            }
        }

        System.out.printf("%n%s%n", allIntact
                ? "every audit chain verifies"
                : "AUDIT CHAIN BROKEN -- the log has been altered, truncated, or reordered");

        if (allIntact && pin == null) {
            System.out.println("Note: a hash chain is tamper-evident, not tamper-proof. Anyone able to "
                             + "write both\n      app.audit_event and app.audit_chain_head could rewrite "
                             + "history and still verify.\n      Use --pin=<file> to keep a copy of the "
                             + "head outside the database.");
        }

        return allIntact ? 0 : 1;
    }

    private void printTail(JdbcTemplate jdbc, long firmId, int count) {
        // A transaction, not an optional nicety: firm context is applied at transaction
        // start, so a bare query has no app.current_firm_id and RLS raises 28000.
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setName("cli:audit-tail");
        definition.setReadOnly(true);
        var template = new org.springframework.transaction.support.TransactionTemplate(
                txProvider.getObject(), definition);

        List<Map<String, Object>> events = FirmContext.runAs(firmId, () -> template.execute(status ->
                jdbc.queryForList("""
                select seq, to_char(occurred_at, 'DD Mon HH24:MI:SS') as at,
                       actor, action, entity_type, entity_id, detail::text as detail
                  from app.audit_event
                 order by seq desc
                 limit ?
                """, count)));

        System.out.println("  recent events:");
        events.forEach(event -> System.out.printf("    #%-5s %s  %-28s %-26s %s %s%n",
                event.get("seq"), event.get("at"), event.get("actor"), event.get("action"),
                event.get("entity_type") == null ? "" : event.get("entity_type") + ":" + event.get("entity_id"),
                event.get("detail")));
    }
}
