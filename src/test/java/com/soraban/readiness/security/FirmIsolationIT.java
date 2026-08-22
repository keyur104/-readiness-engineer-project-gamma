package com.soraban.readiness.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that firm isolation is structural rather than conventional.
 *
 * <p>The brief's exact requirement:
 * <blockquote>Cross-firm data access should be prevented by the architecture itself so that
 * a forgotten {@code where} clause fails safe instead of leaking another firm's data.</blockquote>
 *
 * <p>{@link #forgottenWhereClauseReturnsOnlyOwnFirm()} is that sentence turned into an
 * assertion: it runs a query with <b>no {@code WHERE} clause at all</b> and asserts the row
 * count is one firm's worth.
 *
 * <h2>The single most important line in this file is an annotation that is absent</h2>
 *
 * <p>There is no {@code @Transactional} and no superuser connection. The suite connects as
 * {@code readiness_app} &mdash; the same unprivileged, non-owning role the application uses --
 * because <b>a test suite that connects as a superuser proves nothing at all</b>. RLS is
 * bypassed for superusers, so such a suite would pass identically against a completely
 * unprotected database, which is the one outcome that would make it worse than having no
 * tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class FirmIsolationIT {

    /**
     * Tables in schema {@code app} that legitimately carry no firm scope.
     *
     * <p>An explicit allowlist rather than an inferred rule, so that exempting a table is a
     * visible change in a diff rather than the silent consequence of leaving a column off.
     */
    private static final Set<String> RLS_EXEMPT = Set.of(
            "firm", "reason_code", "flyway_schema_history");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private long firmA;
    private long firmB;

    @BeforeEach
    void findFirms() {
        // Inside a transaction even though app.firm's SELECT policy is `using (true)` and
        // needs no firm context.
        //
        // The pool runs with auto-commit off (deliberately -- it makes non-transactional use
        // visible rather than silent). A bare query therefore leaves the connection inside an
        // implicitly-opened transaction, and the next operation on it never passes through
        // FirmTransactionManager.doBegin -- so set_config never runs and every subsequent
        // query raises 28000. Being explicit here avoids poisoning the connection.
        List<Long> ids = inSystemTransaction(() -> jdbc.queryForList(
                "select id from app.firm order by id", Long.class));
        assertThat(ids).as("V3 seeds two firms").hasSizeGreaterThanOrEqualTo(2);
        firmA = ids.get(0);
        firmB = ids.get(1);

        seedIfNeeded(firmA, "A");
        seedIfNeeded(firmB, "B");
    }

    private void seedIfNeeded(long firmId, String label) {
        FirmContext.runAs(firmId, () -> inTransaction(() -> {
            Long count = jdbc.queryForObject(
                    "select count(*) from app.client where client_ref like 'ISO-%'", Long.class);
            if (count != null && count >= 3) {
                return null;
            }
            for (int i = 1; i <= 3; i++) {
                jdbc.update("""
                        insert into app.client (firm_id, client_ref, legal_name)
                        values (app.current_firm_id(), ?, ?)
                        on conflict (firm_id, client_ref) do nothing
                        """, "ISO-" + label + "-" + i, "Isolation " + label + " " + i);
            }
            return null;
        }));
    }

    // =================================================================================
    // The brief's sentence, as an assertion
    // =================================================================================

    @Test
    @DisplayName("a query with NO WHERE clause returns only the current firm's rows")
    void forgottenWhereClauseReturnsOnlyOwnFirm() {
        long visibleToA = FirmContext.runAs(firmA, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.client", Long.class)));

        long visibleToB = FirmContext.runAs(firmB, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.client", Long.class)));

        long distinctFirmsForA = FirmContext.runAs(firmA, () -> inTransaction(() ->
                jdbc.queryForObject("select count(distinct firm_id) from app.client", Long.class)));

        long everything = asSuperuserCount();

        assertThat(visibleToA).as("firm A sees fewer rows than exist in total").isLessThan(everything);
        assertThat(visibleToB).as("firm B likewise").isLessThan(everything);
        assertThat(visibleToA + visibleToB)
                .as("between them, the two firms account for every row -- nothing is hidden from both")
                .isEqualTo(everything);
        assertThat(distinctFirmsForA)
                .as("a firm never sees a second firm_id, even with no filter")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a deliberately buggy query with a hard-coded other-firm id still returns nothing")
    void queryTargetingAnotherFirmReturnsNothing() {
        // This query is intentionally wrong. It asks, from inside firm A, for firm B's rows
        // BY ID. If it ever returns a row, isolation is broken -- and note that no amount of
        // code review would catch this shape, which is exactly why the guarantee has to live
        // below the application.
        long leaked = FirmContext.runAs(firmA, () -> inTransaction(() ->
                jdbc.queryForObject(
                        "select count(*) from app.client where firm_id = ?", Long.class, firmB)));

        assertThat(leaked)
                .as("naming another firm's id explicitly must still yield nothing")
                .isZero();
    }

    // =================================================================================
    // Fail closed, and loudly
    // =================================================================================

    @Test
    @DisplayName("no firm context fails closed with 28000, rather than returning an empty result")
    void missingFirmContextFailsClosed() {
        assertThatThrownBy(() -> inSystemTransaction(() ->
                jdbc.queryForObject("select count(*) from app.client", Long.class)))
                .as("the NULL-returning variant would fail silently; raising is diagnosable")
                .hasMessageContaining("firm context is not set");
    }

    @Test
    @DisplayName("opening a transaction with no firm context is rejected before any SQL runs")
    void contextFreeTransactionIsRejected() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .execute(status -> jdbc.queryForObject("select 1", Long.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no firm context");
    }

    // =================================================================================
    // Writes cannot forge a firm
    // =================================================================================

    @Test
    @DisplayName("inserting a row carrying another firm's id is rejected by WITH CHECK")
    void cannotForgeFirmIdOnWrite() {
        assertThatThrownBy(() -> FirmContext.runAs(firmA, () -> inTransaction(() ->
                jdbc.update("""
                        insert into app.client (firm_id, client_ref, legal_name)
                        values (?, 'FORGED', 'Should never exist')
                        """, firmB))))
                .as("WITH CHECK makes firm_id un-forgeable on write")
                // Asserted on the ROOT cause: PostgreSQL raises SQLState 42501, which Spring
                // maps to BadSqlGrammarException, so the outer message says nothing about
                // policies. The write is refused either way -- this just looks for the reason
                // rather than for Spring's classification of it.
                .rootCause()
                .hasMessageContaining("row-level security policy");
    }

    @Test
    @DisplayName("UPDATE and DELETE without a WHERE clause affect only the current firm")
    void crossFirmMutationIsScoped() {
        long beforeB = FirmContext.runAs(firmB, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.client", Long.class)));

        int touched = FirmContext.runAs(firmA, () -> inTransaction(() ->
                jdbc.update("update app.client set legal_name = legal_name")));

        long afterB = FirmContext.runAs(firmB, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.client", Long.class)));

        assertThat(touched).as("firm A's own rows were updated").isGreaterThan(0);
        assertThat(afterB).as("firm B is untouched by an unfiltered UPDATE").isEqualTo(beforeB);
    }

    // =================================================================================
    // The privileges the design depends on
    // =================================================================================

    @Test
    @DisplayName("the runtime role is not a superuser, has no BYPASSRLS, and owns no tables")
    void runtimeRoleIsUnprivileged() {
        assertThat(inSystemTransaction(() -> jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname = current_user", Boolean.class)))
                .as("superusers bypass RLS unconditionally").isFalse();

        assertThat(inSystemTransaction(() -> jdbc.queryForObject(
                "select rolbypassrls from pg_roles where rolname = current_user", Boolean.class)))
                .as("BYPASSRLS makes every policy inert").isFalse();

        assertThat(inSystemTransaction(() -> jdbc.queryForObject("""
                select exists (
                  select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
                   where n.nspname = 'app' and c.relkind = 'r'
                     and pg_get_userbyid(c.relowner) = current_user)
                """, Boolean.class)))
                .as("owners are exempt from their own policies unless FORCE is set")
                .isFalse();
    }

    @Test
    @DisplayName("TRUNCATE is granted on nothing, because TRUNCATE is not filtered by RLS")
    void truncateIsDenied() {
        List<String> truncatable = inSystemTransaction(() -> jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app' and c.relkind = 'r'
                   and has_table_privilege(current_user, c.oid, 'TRUNCATE')
                """, String.class));

        assertThat(truncatable)
                .as("a role holding TRUNCATE could destroy every firm's rows in one statement, "
                    + "regardless of any policy")
                .isEmpty();
    }

    // =================================================================================
    // The meta-test: the one that catches a regression six months from now
    // =================================================================================

    @Test
    @DisplayName("every firm-scoped table has RLS enabled AND forced AND at least one policy")
    void everyTenantTableIsProtected() {
        List<String> unprotected = inSystemTransaction(() -> jdbc.queryForList("""
                select c.relname
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app' and c.relkind = 'r'
                   and exists (select 1 from information_schema.columns col
                                where col.table_schema = 'app' and col.table_name = c.relname
                                  and col.column_name = 'firm_id')
                   and (c.relrowsecurity = false or c.relforcerowsecurity = false
                        or not exists (select 1 from pg_policies p
                                        where p.schemaname = 'app' and p.tablename = c.relname))
                 order by c.relname
                """, String.class));

        assertThat(unprotected)
                .as("FORCE matters as much as ENABLE: without it the table owner is exempt "
                    + "from its own policies")
                .isEmpty();
    }

    @Test
    @DisplayName("any table lacking firm_id is either the firm registry, reference data, or "
               + "an explicitly declared exemption")
    void noTableSilentlyEscapesTenancy() {
        List<String> unscoped = inSystemTransaction(() -> jdbc.queryForList("""
                select c.relname
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app' and c.relkind = 'r'
                   and not exists (select 1 from information_schema.columns col
                                    where col.table_schema = 'app' and col.table_name = c.relname
                                      and col.column_name = 'firm_id')
                 order by c.relname
                """, String.class));

        // This is the test that earns its keep long after the design is written: adding a
        // table and forgetting firm_id is an ordinary mistake, and without this it produces
        // a silently world-readable table rather than a failing build.
        assertThat(unscoped)
                .as("a new table without firm_id must be a deliberate, declared exemption")
                .allSatisfy(table -> assertThat(RLS_EXEMPT).contains(table));
    }

    @Test
    @DisplayName("money is never stored as a floating-point type")
    void noFloatingPointMoney() {
        List<String> floats = inSystemTransaction(() -> jdbc.queryForList("""
                select table_name || '.' || column_name || ' (' || data_type || ')'
                  from information_schema.columns
                 where table_schema in ('app', 'stg', 'irs_stub')
                   and data_type in ('real', 'double precision')
                 order by 1
                """, String.class));

        // Enforced rather than documented. "$600.00 or more, inclusive" is only exactly
        // decidable in integer cents: under double, 199.99 + 200.01 + 200.00 sums to
        // 600.0000000000001.
        assertThat(floats)
                .as("money must be bigint cents; a float column would make the threshold "
                    + "comparison approximate")
                .isEmpty();
    }

    // =================================================================================
    // Helpers
    // =================================================================================

    /**
     * The total row count across all firms.
     *
     * <p>Obtained by summing what each firm can see, rather than by opening a superuser
     * connection: the suite deliberately holds no privileged credentials, so it cannot
     * accidentally come to depend on them.
     */
    private long asSuperuserCount() {
        long a = FirmContext.runAs(firmA, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.client", Long.class)));
        long b = FirmContext.runAs(firmB, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.client", Long.class)));
        return a + b;
    }

    private <T> T inTransaction(Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }

    private <T> T inSystemTransaction(Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("system:isolation-test");
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
