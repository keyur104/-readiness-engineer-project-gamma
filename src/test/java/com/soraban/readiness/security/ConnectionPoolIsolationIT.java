package com.soraban.readiness.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Firm context does not survive a connection going back to the pool.
 *
 * <h2>The hazard this exists to close</h2>
 *
 * <p>A pooled connection is a long-lived <em>server session</em>. A session-level {@code SET}
 * outlives the request that issued it, so the next borrower of that connection inherits it
 * &mdash; a cross-tenant leak <b>caused by</b> the isolation mechanism itself, and one that
 * appears only under connection reuse, which is to say only in production.
 *
 * <p>The design's answer is that session-level {@code SET} is banned outright. Firm context is
 * only ever established with {@code set_config('app.current_firm_id', ?, true)}, whose third
 * argument makes it transaction-local: PostgreSQL unwinds it at {@code COMMIT} or
 * {@code ROLLBACK}. There is no cleanup code to forget, because there is no cleanup code.
 *
 * <p>And there is deliberately <b>no {@code DISCARD ALL}, no reset SQL and no
 * {@code connectionInitSql}</b>. Adding a belt-and-braces reset would say the primary mechanism
 * is not trusted, and a reset that silently papers over a leak is worse than none &mdash; it
 * removes the only signal that something is wrong. That decision is only defensible if the
 * primary mechanism actually holds, which is what this test is for.
 *
 * <h2>Pool size 1</h2>
 *
 * <p>With one connection, "the next borrower" is guaranteed to be the same physical session.
 * At the default pool size the second transaction would probably get a different connection
 * and the test would pass without ever exercising reuse &mdash; a green tick for a check that
 * never ran.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class ConnectionPoolIsolationIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("the firm setting is gone when the same connection is handed out again")
    void transactionLocalContextIsUnwoundOnReturnToThePool() {
        long firmId = system(() -> jdbc.queryForObject(
                "select id from app.firm where slug = 'northstar'", Long.class));

        // Establish context, and confirm it really is set inside the transaction -- otherwise
        // the assertion afterwards could pass simply because it was never set at all.
        String insideTransaction = FirmContext.runAs(firmId, () -> firmScoped(() ->
                jdbc.queryForObject("select current_setting('app.current_firm_id', true)",
                        String.class)));

        assertThat(insideTransaction)
                .as("the context must be set inside the transaction, or this test proves nothing")
                .isEqualTo(Long.toString(firmId));

        // Same connection, next borrower. A system transaction, because that is the one kind
        // that does NOT set the value -- so whatever is read here is a genuine leftover rather
        // than something this transaction just wrote.
        String afterReturnToPool = system(() -> jdbc.queryForObject(
                "select current_setting('app.current_firm_id', true)", String.class));

        assertThat(afterReturnToPool)
                .as("a pooled connection must not carry firm context back out of the pool; "
                  + "if this fails, the next borrower reads another firm's rows")
                .satisfiesAnyOf(
                        value -> assertThat(value).isNull(),
                        value -> assertThat(value).isEmpty());
    }

    @Test
    @DisplayName("a rolled-back transaction leaves no context behind either")
    void rollbackAlsoUnwindsTheSetting() {
        long firmId = system(() -> jdbc.queryForObject(
                "select id from app.firm where slug = 'harborline'", Long.class));

        // COMMIT is the easy case. ROLLBACK is the one worth checking, because a mechanism that
        // cleaned up only on the happy path would leak on exactly the requests that failed --
        // and failures are when a connection is most likely to be recycled in a hurry.
        try {
            FirmContext.runAs(firmId, () -> firmScoped(() -> {
                jdbc.queryForObject("select current_setting('app.current_firm_id', true)",
                        String.class);
                throw new IllegalStateException("deliberate failure after establishing context");
            }));
        } catch (IllegalStateException expected) {
            // The transaction rolled back. That is the point.
        }

        String afterRollback = system(() -> jdbc.queryForObject(
                "select current_setting('app.current_firm_id', true)", String.class));

        assertThat(afterRollback).satisfiesAnyOf(
                value -> assertThat(value).isNull(),
                value -> assertThat(value).isEmpty());
    }

    @Test
    @DisplayName("two firms in sequence on one connection never see each other's context")
    void consecutiveFirmsOnOneConnectionStayIndependent() {
        long firmA = system(() -> jdbc.queryForObject(
                "select id from app.firm where slug = 'northstar'", Long.class));
        long firmB = system(() -> jdbc.queryForObject(
                "select id from app.firm where slug = 'harborline'", Long.class));

        // The realistic shape of the bug: not "does a setting linger" in the abstract, but
        // "does request two, on the recycled connection, see request one's tenant".
        String seenByA = FirmContext.runAs(firmA, () -> firmScoped(() ->
                jdbc.queryForObject("select current_setting('app.current_firm_id', true)",
                        String.class)));
        String seenByB = FirmContext.runAs(firmB, () -> firmScoped(() ->
                jdbc.queryForObject("select current_setting('app.current_firm_id', true)",
                        String.class)));

        assertThat(seenByA).isEqualTo(Long.toString(firmA));
        assertThat(seenByB)
                .as("the second firm must see its own id, not the first firm's")
                .isEqualTo(Long.toString(firmB));
    }

    // =================================================================================

    /** An ordinary firm-scoped transaction: the manager stamps the context at doBegin. */
    private <T> T firmScoped(Supplier<T> body) {
        return transaction("test:pool-reuse", body);
    }

    /** Opts out of context stamping, so a read here observes whatever was already there. */
    private <T> T system(Supplier<T> body) {
        return transaction("system:pool-probe", body);
    }

    private <T> T transaction(String name, Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
