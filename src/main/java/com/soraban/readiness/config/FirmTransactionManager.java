package com.soraban.readiness.config;

import com.soraban.readiness.security.FirmContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Pushes the current firm into the database session at transaction start, so that
 * PostgreSQL Row-Level Security can filter every subsequent statement.
 *
 * <p>This class is where firm isolation stops being a convention and becomes a
 * property of the system: it is not possible to open a transaction without firm
 * context, because opening a transaction is what sets it.
 *
 * <h2>Why {@code set_config(..., is_local => true)} and not {@code SET}</h2>
 *
 * <p>A pooled connection is a long-lived server session, so a session-level {@code SET}
 * outlives the request that issued it. If request A sets firm 1 session-wide and
 * request B for firm 2 receives that same connection but the plumbing fails to
 * overwrite it, B silently reads firm 1's data &mdash; a cross-tenant leak <em>caused
 * by</em> the isolation mechanism. Three properties of the transaction-local form
 * remove that class of bug:
 *
 * <ol>
 *   <li><b>Postgres reverts it at COMMIT or ROLLBACK.</b> The connection cannot be
 *       returned to the pool still carrying the setting. There is no cleanup code to
 *       forget, because there is no cleanup code.</li>
 *   <li><b>Outside a transaction block it does nothing.</b> A query issued in
 *       autocommit mode therefore has no firm context at all and hits the {@code 28000}
 *       exception from {@code app.current_firm_id()}. Non-transactional access fails
 *       closed <em>and</em> loudly.</li>
 *   <li><b>It takes a bind parameter.</b> {@code SET LOCAL app.current_firm_id = ...}
 *       is a utility statement and cannot be parameterized, which would force string
 *       concatenation and reintroduce injection into the security control itself.</li>
 * </ol>
 *
 * <p>It is also the form that stays correct behind PgBouncer in transaction-pooling
 * mode, where a session-level {@code SET} would be catastrophically wrong.
 *
 * <h2>Nesting and suspension</h2>
 *
 * <p>Handled for free. {@code PROPAGATION_REQUIRED} joins the outer transaction and so
 * inherits its setting; {@code REQUIRES_NEW} calls {@link #doBegin} again on its own
 * connection and re-applies the context; a savepoint rollback reverts the setting to
 * its value at savepoint time, which is the same value.
 *
 * <h2>System transactions</h2>
 *
 * <p>A small number of operations legitimately have no firm: Flyway migrations, the
 * {@code RlsGuard} startup assertion (which reads {@code pg_catalog} only), and the
 * reconciliation sweep that must inspect every firm's batches before workers start.
 * These opt out by naming their transaction with the {@link #SYSTEM_TX_PREFIX}. Every
 * other context-free transaction is a bug and is rejected here rather than being
 * allowed to produce a confusing {@code 28000} several statements later.
 */
public class FirmTransactionManager extends JdbcTransactionManager {

    /**
     * Transactions whose name starts with this may run without firm context. Naming is
     * used rather than an annotation so the exemption is visible at the call site and
     * greppable across the codebase.
     */
    public static final String SYSTEM_TX_PREFIX = "system:";

    private static final String SET_FIRM_SQL = "select set_config('app.current_firm_id', ?, true)";

    public FirmTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Long firmId = FirmContext.currentOrNull();

        // REJECT BEFORE super.doBegin(), not after.
        //
        // super.doBegin() acquires a pooled connection and binds it to the thread. Throwing
        // afterwards leaves that connection bound but never released, because Spring's
        // cleanup path does not run for an exception raised inside doBegin itself. Every
        // rejected transaction would therefore leak one connection -- and since this guard
        // fires precisely when something is already going wrong, the pool would drain at the
        // worst possible moment.
        //
        // Validating first means a transaction we are going to refuse never costs a
        // connection at all.
        if (firmId == null && !isSystemTransaction(definition)) {
            throw new IllegalStateException(
                    "transaction opened with no firm context: "
                            + describe(definition)
                            + " -- wrap the work in FirmContext.runAs(firmId, ...), or name the "
                            + "transaction with the \"" + SYSTEM_TX_PREFIX + "\" prefix if it is "
                            + "genuinely firm-independent");
        }

        super.doBegin(transaction, definition);

        if (firmId != null) {
            applyFirmContext(firmId);
        }
    }

    /**
     * Issues the transaction-local setting on the connection Spring has just bound to
     * this transaction. Uses {@link DataSourceUtils} rather than the raw DataSource so
     * we get the connection already enlisted in the transaction, not a fresh one from
     * the pool &mdash; setting the context on a different connection than the one the
     * work runs on would be a silent no-op.
     */
    private void applyFirmContext(long firmId) {
        Connection connection = DataSourceUtils.getConnection(obtainDataSource());
        try (PreparedStatement ps = connection.prepareStatement(SET_FIRM_SQL)) {
            ps.setString(1, Long.toString(firmId));
            ps.execute();
        } catch (SQLException e) {
            throw translateException("set firm context", e);
        }
    }

    private boolean isSystemTransaction(TransactionDefinition definition) {
        String name = definition.getName();
        return name != null && name.startsWith(SYSTEM_TX_PREFIX);
    }

    private String describe(TransactionDefinition definition) {
        String name = definition.getName();
        return name != null ? name : "<unnamed>";
    }
}
