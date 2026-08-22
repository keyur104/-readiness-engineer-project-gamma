package com.soraban.readiness.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start the application unless firm isolation is actually in force.
 *
 * <p>Row-Level Security has an unusually bad failure mode: every way of breaking it is
 * silent. Connect as a superuser, connect as the table owner without {@code FORCE},
 * grant {@code BYPASSRLS} for a debugging session and forget to revoke it, or add a new
 * table and forget the policy &mdash; in every case the application keeps working
 * perfectly and simply stops isolating firms. Nothing throws. Nothing logs.
 *
 * <p>So the invariants are asserted on every boot, and a violation is fatal. This costs
 * one round trip at startup and converts the entire class of silent failure into a
 * process that will not start.
 *
 * <p>This is deliberately production code rather than a test. The test suite proves the
 * policies work; this proves they are still switched on in whatever environment the app
 * actually booted into &mdash; including a reviewer's machine after a hand-run
 * migration or a psql session.
 */
@Component
@Order(RlsGuard.ORDER)
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class RlsGuard implements ApplicationRunner {

    /** Runs before any other runner, including the reconciliation sweep. */
    public static final int ORDER = Integer.MIN_VALUE + 100;

    private static final Logger log = LoggerFactory.getLogger(RlsGuard.class);

    /**
     * Tables in schema {@code app} that legitimately carry no firm scope.
     *
     * <p>Kept as an explicit allowlist rather than inferred, so that exempting a table
     * is a visible code change that a reviewer sees in the diff, rather than a silent
     * consequence of leaving a column off.
     */
    private static final Set<String> RLS_EXEMPT_TABLES = Set.of(
            "reason_code",              // reference data; identical for every firm
            "flyway_schema_history"     // migration metadata; owned by the owner role
    );

    /**
     * Per-run COPY staging tables ({@code stg_ledger_line_<runId>}) and their template.
     *
     * <p>Exempt by prefix rather than by name because the per-run tables are created at
     * runtime and their names are not knowable in advance. They are private to one import,
     * dropped when it finishes, and never queried by anything else &mdash; the firm_id is
     * stamped by the {@code INSERT ... SELECT} that moves rows into {@code ledger_line},
     * where the {@code WITH CHECK} policy validates it.
     *
     * <p>Matching on a prefix also means a staging table left behind by a <em>failed</em>
     * import cannot block the next boot. Retaining those deliberately (so they can be
     * inspected) would otherwise turn a debugging aid into a startup failure.
     */
    private static final String STAGING_TABLE_PREFIX = "stg_";

    private final JdbcTemplate jdbc;
    private final String expectedUser;

    public RlsGuard(JdbcTemplate jdbc,
                    @Value("${spring.datasource.username}") String expectedUser) {
        this.jdbc = jdbc;
        this.expectedUser = expectedUser;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = new ArrayList<>();

        checkRuntimeRole(violations);
        checkRlsEnabledEverywhere(violations);
        checkPoliciesExist(violations);
        checkNoTruncatePrivilege(violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "firm isolation is not in force; refusing to start:"
                            + System.lineSeparator()
                            + String.join(System.lineSeparator(),
                                          violations.stream().map(v -> "  - " + v).toList()));
        }

        log.info("rls_guard status=OK user={} checks=role,force_rls,policies,truncate", expectedUser);
    }

    /**
     * The runtime role must be exactly who we think it is, and must hold none of the
     * three privileges that bypass RLS outright.
     *
     * <p>Superusers ignore RLS unconditionally. {@code BYPASSRLS} does the same. And
     * membership in the owning role matters because a table's owner is exempt from its
     * own policies unless the table is declared {@code FORCE ROW LEVEL SECURITY} --
     * we set FORCE everywhere, but a role that can become the owner can also alter the
     * table, so the check stays.
     */
    private void checkRuntimeRole(List<String> violations) {
        String currentUser = jdbc.queryForObject("select current_user", String.class);
        if (!expectedUser.equals(currentUser)) {
            violations.add("connected as '%s' but configuration expects '%s'"
                    .formatted(currentUser, expectedUser));
        }

        Boolean isSuper = jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname = current_user", Boolean.class);
        if (Boolean.TRUE.equals(isSuper)) {
            violations.add("runtime role '%s' is a SUPERUSER; superusers bypass RLS entirely"
                    .formatted(currentUser));
        }

        Boolean bypassRls = jdbc.queryForObject(
                "select rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
        if (Boolean.TRUE.equals(bypassRls)) {
            violations.add("runtime role '%s' has BYPASSRLS; every policy is inert for it"
                    .formatted(currentUser));
        }

        Boolean ownsTables = jdbc.queryForObject("""
                select exists (
                  select 1 from pg_class c
                    join pg_namespace n on n.oid = c.relnamespace
                   where n.nspname = 'app' and c.relkind = 'r'
                     and pg_get_userbyid(c.relowner) = current_user)
                """, Boolean.class);
        if (Boolean.TRUE.equals(ownsTables)) {
            violations.add(("runtime role '%s' owns tables in schema app; owners are exempt from "
                    + "their own policies unless FORCE is set, and can drop the policies outright")
                    .formatted(currentUser));
        }
    }

    /** Every firm-scoped table must have RLS both enabled and forced. */
    private void checkRlsEnabledEverywhere(List<String> violations) {
        List<String> unprotected = jdbc.queryForList("""
                select c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app'
                   and c.relkind = 'r'
                   and exists (select 1 from information_schema.columns col
                                where col.table_schema = 'app'
                                  and col.table_name = c.relname
                                  and col.column_name = 'firm_id')
                   and (c.relrowsecurity = false or c.relforcerowsecurity = false)
                 order by c.relname
                """, String.class);

        for (String table : unprotected) {
            violations.add(("table app.%s has a firm_id column but is missing ENABLE and/or FORCE "
                    + "ROW LEVEL SECURITY").formatted(table));
        }

        // The inverse check: a table with no firm_id that is also not allowlisted is
        // either missing its tenancy column or is a genuine exemption nobody declared.
        List<String> unscoped = jdbc.queryForList("""
                select c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app'
                   and c.relkind = 'r'
                   and not exists (select 1 from information_schema.columns col
                                    where col.table_schema = 'app'
                                      and col.table_name = c.relname
                                      and col.column_name = 'firm_id')
                 order by c.relname
                """, String.class);

        for (String table : unscoped) {
            if (!RLS_EXEMPT_TABLES.contains(table)
                    && !table.startsWith(STAGING_TABLE_PREFIX)
                    && !"firm".equals(table)) {
                violations.add(("table app.%s has no firm_id column and is not in the RLS exemption "
                        + "allowlist; add firm_id or declare the exemption explicitly")
                        .formatted(table));
            }
        }
    }

    /** Enabled-but-policy-less would deny everything; enabled with a wrong policy is worse. */
    private void checkPoliciesExist(List<String> violations) {
        List<String> policyless = jdbc.queryForList("""
                select c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app'
                   and c.relkind = 'r'
                   and c.relrowsecurity = true
                   and not exists (select 1 from pg_policies p
                                    where p.schemaname = 'app' and p.tablename = c.relname)
                 order by c.relname
                """, String.class);

        for (String table : policyless) {
            violations.add("table app.%s has RLS enabled but no policy at all".formatted(table));
        }
    }

    /**
     * TRUNCATE is not filtered by row-level security. A role holding it can destroy
     * every firm's rows in one statement regardless of any policy on the table, so the
     * absence of the privilege is itself a security control worth asserting.
     */
    private void checkNoTruncatePrivilege(List<String> violations) {
        List<String> truncatable = jdbc.queryForList("""
                select c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app'
                   and c.relkind = 'r'
                   and has_table_privilege(current_user, c.oid, 'TRUNCATE')
                 order by c.relname
                """, String.class);

        for (String table : truncatable) {
            violations.add(("runtime role holds TRUNCATE on app.%s; TRUNCATE bypasses RLS and would "
                    + "delete every firm's rows").formatted(table));
        }
    }
}
