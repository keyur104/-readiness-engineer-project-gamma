package com.soraban.readiness.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves {@code username@firm-slug} into a {@link FirmUser}, in two firm-scoped reads.
 *
 * <p>Log-in is the one operation that must run <em>before</em> a firm context exists, and
 * it is resolved without any new privilege mechanism &mdash; see the header of
 * {@code V9__auth.sql} for why a {@code SECURITY DEFINER} function is both the obvious
 * answer and the wrong one under {@code FORCE ROW LEVEL SECURITY}.
 *
 * <ol>
 *   <li>Resolve the slug against {@code app.firm} in a system transaction. That table's
 *       SELECT policy is deliberately open, because resolving a firm is precisely what has
 *       to happen before a firm context can be established.</li>
 *   <li>Read {@code app.app_user} inside {@code FirmContext.runAs(thatFirm)}, under the
 *       ordinary policy. A firm's staff list stays firm-scoped.</li>
 * </ol>
 *
 * <p>The hash is compared by Spring Security in the application, never by the database.
 * Passing a plaintext password into SQL would put it in the statement text and therefore
 * into {@code pg_stat_activity}, {@code pg_stat_statements} and any statement log &mdash;
 * the same argument that keeps TIN encryption out of {@code pgcrypto}.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class FirmUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    public FirmUserDetailsService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    @Override
    public FirmUser loadUserByUsername(String loginName) throws UsernameNotFoundException {
        int at = loginName.lastIndexOf('@');
        if (at <= 0 || at == loginName.length() - 1) {
            // Same message for a malformed login as for an unknown one. A distinct
            // "that firm does not exist" would turn the login form into a firm directory.
            throw new UsernameNotFoundException("bad credentials");
        }

        String username = loginName.substring(0, at);
        String firmSlug = loginName.substring(at + 1);

        Long firmId = inSystemTransaction(() -> jdbc.query(
                "select id from app.firm where slug = ?",
                rs -> rs.next() ? rs.getLong(1) : null, firmSlug));

        if (firmId == null) {
            throw new UsernameNotFoundException("bad credentials");
        }

        List<Map<String, Object>> rows = FirmContext.runAs(firmId, () -> inFirmTransaction(() ->
                jdbc.queryForList("""
                        select id, firm_id, username, display_name, role, password_hash, disabled
                          from app.app_user
                         where username = ?
                        """, username)));

        if (rows.isEmpty()) {
            throw new UsernameNotFoundException("bad credentials");
        }

        Map<String, Object> row = rows.get(0);
        String hash = (String) row.get("password_hash");
        if (hash == null) {
            // A user row with no credential is not a user who can log in with any password.
            // Left explicit rather than relying on the encoder to reject null, because
            // "the encoder happens to fail" is not an authentication decision.
            throw new UsernameNotFoundException("bad credentials");
        }

        return new FirmUser(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("firm_id")).longValue(),
                firmSlug,
                (String) row.get("username"),
                (String) row.get("display_name"),
                (String) row.get("role"),
                hash,
                !((Boolean) row.get("disabled")));
    }

    /**
     * Named with the {@code system:} prefix so {@code FirmTransactionManager} does not
     * demand a firm context. Used for exactly one query &mdash; the {@code app.firm}
     * lookup that establishes the context in the first place.
     */
    private <T> T inSystemTransaction(Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("system:firm-lookup");
        definition.setReadOnly(true);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }

    /**
     * An ordinary firm-scoped transaction: the manager stamps {@code app.current_firm_id}
     * from {@link FirmContext} at {@code doBegin}, so the staff-list read below is filtered
     * by the same policy as every other read in the system. Deliberately NOT a system
     * transaction &mdash; the whole point is that authentication does not get an exemption.
     */
    private <T> T inFirmTransaction(Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("auth:load-user");
        definition.setReadOnly(true);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
