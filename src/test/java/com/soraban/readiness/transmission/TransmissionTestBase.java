package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.security.Tin;
import com.soraban.readiness.security.TinCryptoService;
import com.soraban.readiness.transmission.domain.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shared setup for transmission tests.
 *
 * <h2>Two things here are deliberate and load-bearing</h2>
 *
 * <p><b>No {@code @Transactional} on the test class.</b> Spring's rollback-per-test is the
 * single most likely way to make a crash-recovery test lie: it wraps everything in one outer
 * transaction that never commits, which hides exactly the commit boundaries that are the
 * entire subject under test. A "crash" inside that outer transaction would roll back work
 * the real system would have committed, and the test would assert against a state the
 * production code can never actually be in.
 *
 * <p>Isolation comes from truncating between tests instead. Slower, and correct.
 *
 * <p><b>The test connects as {@code readiness_app}</b>, from {@code application-test.yml} --
 * the same unprivileged role the application uses. A suite that connected as a superuser
 * would bypass RLS entirely and would pass identically against a completely unprotected
 * database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(CrashRecoveryHarness.class)
public abstract class TransmissionTestBase {

    protected static final int TAX_YEAR = 2025;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected TinCryptoService tinCrypto;
    @Autowired protected CrashRecoveryHarness.ArmedCrashHooks crashHooks;
    @Autowired protected InvariantChecker invariantChecker;

    protected long firmId;

    @BeforeEach
    void resetWorld() {
        crashHooks.disarm();

        firmId = jdbc.queryForObject(
                "select id from app.firm where slug = 'northstar'", Long.class);

        // DELETE, not TRUNCATE.
        //
        // TRUNCATE is deliberately revoked from readiness_app (it is not filtered by RLS, so
        // a role holding it could wipe every firm's rows in one statement regardless of any
        // policy). The test suite connects as that same unprivileged role -- on purpose,
        // because a suite running as a superuser would bypass RLS entirely and would pass
        // identically against a completely unprotected database.
        //
        // So the reset has to work within the application's own privileges. That is a feature:
        // it means the tests cannot accidentally rely on something production cannot do.
        // Deleting in FK order rather than using CASCADE, for the same reason.
        FirmContext.runAs(firmId, () -> inTransaction(() -> {
            jdbc.execute("delete from app.filing_batch_member");
            jdbc.execute("delete from app.transmission_attempt");
            jdbc.execute("delete from app.attention_item");
            jdbc.execute("delete from app.irs_call_log");
            jdbc.execute("delete from app.pending_amendment");
            jdbc.execute("delete from app.filing_batch");
            jdbc.execute("delete from app.filing");
            return null;
        }));

        // The stub's schema models an external system: no firm scoping, no RLS, and the app
        // role holds ordinary DML on it.
        inSystemTransaction(() -> {
            jdbc.execute("delete from irs_stub.recorded_filing");
            jdbc.execute("delete from irs_stub.call_log");
            jdbc.execute("delete from irs_stub.submission");
            return null;
        });

        seedClientsIfAbsent();
    }

    /**
     * A handful of clients, enough to produce several batches without importing a corpus.
     *
     * <p>Deliberately outside the truncation above: clients are reference data for these
     * tests, and recreating them per test would multiply the suite's runtime for no extra
     * coverage.
     */
    private void seedClientsIfAbsent() {
        // Unconditional upsert, NOT a guard on "are there any clients at all".
        //
        // The guarded version passed for months and then broke the moment another test
        // imported a real corpus: the table was no longer empty, so these three fixtures were
        // never recreated, and every transmission test failed looking up client 'T-1'. "Absent"
        // has to mean "these rows are absent", not "the table is empty" -- the second is a
        // statement about other tests' data, which is exactly the coupling a reset is for
        // removing.
        FirmContext.runAs(firmId, () -> inTransaction(() -> {
            for (int i = 1; i <= 3; i++) {
                jdbc.update("""
                        insert into app.client (firm_id, client_ref, legal_name)
                        values (app.current_firm_id(), ?, ?)
                        on conflict (firm_id, client_ref) do nothing
                        """, "T-" + i, "Test Client " + i);
            }
            return null;
        }));
    }

    /**
     * Creates transmittable filings for one client.
     *
     * <p>Built directly rather than by running import and determination, so a crash test is
     * about the transmission path alone. Filing ids still come from the production
     * {@link IdempotencyKey#filingId} derivation, so the identity properties under test are
     * the real ones rather than a test-only shortcut.
     */
    protected void givenReadyFilings(String clientRef, int count) {
        FirmContext.runAs(firmId, () -> inTransaction(() -> {
            long clientId = jdbc.queryForObject(
                    "select id from app.client where client_ref = ?", Long.class, clientRef);

            for (int i = 0; i < count; i++) {
                String vendorKey = "TIN:test-" + clientRef + "-" + i;
                UUID filingId = IdempotencyKey.filingId(firmId, clientId, TAX_YEAR, vendorKey);

                // Nine digits, not 000-prefixed, so preflight passes and the stub accepts:
                // these tests are about crash recovery, not about validation.
                String digits = "1" + String.valueOf(100_000_000L + i).substring(1);
                Tin tin = Tin.parse(digits, "EIN").tin();
                byte[] bidx = tinCrypto.blindIndex(firmId, tin);
                TinCryptoService.Encrypted encrypted = tinCrypto.encrypt(firmId, clientId, bidx, tin);

                long amount = 75_000L + i * 100L;
                byte[] contentHash = IdempotencyKey.contentHash(
                        filingId, 1, TAX_YEAR, "PAYER-EIN", digits, "EIN",
                        "Vendor " + i, amount, 0L);

                jdbc.update("""
                        insert into app.filing (
                            id, firm_id, client_id, tax_year, vendor_key, state, content_hash,
                            amount_cents, withholding_cents, recipient_name,
                            recipient_tin_ct, recipient_tin_bidx, tin_last4, tin_status)
                        values (?, app.current_firm_id(), ?, ?, ?, 'READY_TO_TRANSMIT', ?,
                                ?, 0, ?, ?, ?, ?, 'PRESENT')
                        on conflict do nothing
                        """,
                        filingId, clientId, TAX_YEAR, vendorKey, contentHash,
                        amount, "Vendor " + i,
                        encrypted.ciphertext(), bidx, tin.last4());
            }
            return null;
        }));
    }

    // =================================================================================
    // Assertions asked of the IRS's own books, not of our beliefs
    // =================================================================================

    protected long filingsRecordedAtIrs() {
        return jdbc.queryForObject(
                "select count(*) from irs_stub.recorded_filing", Long.class);
    }

    /** The core anti-duplicate assertion: how many filings the endpoint recorded twice. */
    protected long duplicatesAtIrs() {
        return jdbc.queryForObject("""
                select count(*) from (
                  select client_reference, filing_generation
                    from irs_stub.recorded_filing
                   group by client_reference, filing_generation
                  having count(*) > 1) d
                """, Long.class);
    }

    /**
     * Filings live at the endpoint that our system does not believe it ever sent.
     *
     * <p>The mode-B leak assertion. If this is ever non-zero, the write-ahead barrier failed
     * and we have shipped something we have no record of shipping.
     */
    protected long leakedFilings() {
        // Joins app.filing, which is under RLS -- so this needs firm context AND a
        // transaction, unlike the pure irs_stub queries above.
        return FirmContext.runAs(firmId, () -> inTransaction(() -> jdbc.queryForObject("""
                select count(*)
                  from irs_stub.recorded_filing r
                 where not exists (
                       select 1 from app.filing f
                        where f.id::text = r.client_reference
                          and f.state in ('SUBMITTED_UNACKNOWLEDGED', 'ACCEPTED', 'REJECTED'))
                """, Long.class)));
    }

    protected long countFilingsInState(String state) {
        return FirmContext.runAs(firmId, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.filing where state = ?",
                        Long.class, state)));
    }

    protected long countBatchesInState(String state) {
        return FirmContext.runAs(firmId, () -> inTransaction(() ->
                jdbc.queryForObject("select count(*) from app.filing_batch where state = ?",
                        Long.class, state)));
    }

    // =================================================================================
    // Transaction helpers
    // =================================================================================

    protected <T> T inTransaction(Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }

    /** For setup that legitimately has no firm, such as truncation. */
    protected <T> T inSystemTransaction(Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("system:test-setup");
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }

    // =================================================================================
    // Shared crash-scenario helpers
    //
    // Lifted here from KillAndResumeIT when a second crash suite appeared. Duplicating them
    // would have meant two definitions of "the invariants hold", and the moment those drift
    // one suite starts passing against a weaker bar than the other.
    // =================================================================================

    /**
     * Asserts the body died the way a real crash dies.
     *
     * <p>{@code SimulatedKill} extends {@code Error}, specifically so that no
     * {@code catch (Exception)} anywhere in the transmission path can swallow it and quietly
     * turn a simulated crash into a handled retry. If this ever catches something else, the
     * crash was absorbed and the test measured the wrong path entirely.
     */
    protected void assertThatSimulatedKill(Runnable body) {
        Throwable caught = null;
        try {
            body.run();
        } catch (Throwable t) {
            caught = t;
        }
        org.assertj.core.api.Assertions.assertThat(caught)
                .as("the simulated crash must escape every handler in the transmission path")
                .isInstanceOf(CrashHooks.SimulatedKill.class);
    }

    protected void assertNoLeaks() {
        org.assertj.core.api.Assertions.assertThat(leakedFilings())
                .as("nothing may be live at the IRS that our system does not know it sent")
                .isZero();
    }

    protected void assertInvariantsHold() {
        InvariantChecker.Report report = invariantChecker.check(firmId);
        org.assertj.core.api.Assertions.assertThat(report.failures())
                .as("all production invariants must hold after recovery")
                .isEmpty();
    }

    protected java.util.UUID anySealedBatch() {
        return FirmContext.runAs(firmId, () -> inTransaction(() ->
                jdbc.queryForObject(
                        "select id from app.filing_batch where state = 'SEALED' limit 1",
                        java.util.UUID.class)));
    }

    protected long clientId(String clientRef) {
        return FirmContext.runAs(firmId, () -> inTransaction(() ->
                jdbc.queryForObject("select id from app.client where client_ref = ?",
                        Long.class, clientRef)));
    }
}
