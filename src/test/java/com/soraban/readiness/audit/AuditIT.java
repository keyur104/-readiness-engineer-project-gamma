package com.soraban.readiness.audit;

import com.soraban.readiness.security.FirmContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the audit log must be true about.
 *
 * <p>Three properties, and they fail in three different ways, so each gets its own test:
 * the log must record what happened, must <b>not</b> record what did not happen, and must
 * make any later alteration detectable.
 *
 * <p>The last group is the interesting one. Each test tampers in a specific way &mdash;
 * edit a row, delete one from the middle, truncate the tail &mdash; and asserts that
 * verification notices. A chain that has never been shown to detect anything is a decoration,
 * exactly like a property test that has never been seen to fail.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditIT {

    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private long firmId;

    /**
     * A per-run marker suffix.
     *
     * <p>The audit log cannot be reset between runs -- that is the property under test -- so
     * rows from every previous execution are still there. Looking a fixture row up by its
     * action alone therefore starts returning three rows on the third run, which is a bug in
     * the test rather than in the log. Tagging makes each run's markers unique.
     */
    private String tag;

    @BeforeEach
    void resetChain() {
        tag = "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        firmId = inSystem(() -> jdbc.queryForObject(
                "select id from app.firm where slug = 'northstar'", Long.class));

        // The suite connects as readiness_app, which cannot DELETE from audit_event by
        // design -- that is the property under test. Resetting therefore happens as the
        // OWNER would not help either (the trigger stops it too), so instead each test
        // works from the chain's current position rather than from an empty table. That is
        // closer to reality anyway: a real audit log is never empty.
    }

    // =================================================================================
    // It records what happened
    // =================================================================================

    @Test
    @DisplayName("an appended event lands in the chain and the head advances")
    void appendingAdvancesTheChain() {
        AuditService.Verification before = verify();
        long seqBefore = before.events();

        inFirmWrite(() -> {
            audit.record(AuditService.Event.human(
                    "sam@northstar", "PREPARER", "TEST_EVENT", "FILING", "abc",
                    Map.of("note", "hello")));
            return null;
        });

        AuditService.Verification after = verify();
        assertThat(after.events()).isEqualTo(seqBefore + 1);
        assertThat(after.intact()).isTrue();
        assertThat(after.headHex()).isNotEqualTo(before.headHex());
    }

    @Test
    @DisplayName("many events in one transaction are appended in order, as one chain segment")
    void batchedEventsKeepTheirOrder() {
        long before = verify().events();

        inFirmWrite(() -> {
            for (int i = 0; i < 20; i++) {
                audit.record(AuditService.Event.system(
                        "test", "BULK_EVENT", "N", Integer.toString(i), Map.of("i", i)));
            }
            return null;
        });

        assertThat(verify().events()).isEqualTo(before + 20);
        assertThat(verify().intact()).isTrue();

        List<Map<String, Object>> recent = inFirmRead(() -> jdbc.queryForList("""
                select seq, entity_id from app.audit_event
                 where action = 'BULK_EVENT' order by seq desc limit 20
                """));
        // Buffered events flush in insertion order, so entity_id 19 has the highest seq.
        assertThat(recent.get(0).get("entity_id")).isEqualTo("19");
    }

    // =================================================================================
    // It does NOT record what did not happen
    // =================================================================================

    @Test
    @DisplayName("events buffered in a transaction that rolls back are discarded")
    void aRolledBackTransactionLeavesNoTrace() {
        long before = verify().events();

        assertThatThrownBy(() -> inFirmWrite(() -> {
            audit.record(AuditService.Event.human(
                    "sam@northstar", "PREPARER", "SHOULD_NOT_EXIST", "FILING", "x", Map.of()));
            throw new IllegalStateException("the work failed after the event was recorded");
        })).isInstanceOf(IllegalStateException.class);

        // The event described work that did not happen. A false entry in the one record
        // that exists to be trusted is worse than a missing one, because nothing downstream
        // contradicts it.
        assertThat(verify().events()).isEqualTo(before);
        assertThat(inFirmRead(() -> jdbc.queryForObject(
                "select count(*) from app.audit_event where action = 'SHOULD_NOT_EXIST'",
                Long.class))).isZero();
    }

    @Test
    @DisplayName("a failure-path event written out of band survives the rollback that caused it")
    void theRefusalRecordOutlivesTheRefusedWork() {
        long before = verify().events();

        assertThatThrownBy(() -> inFirmWrite(() -> {
            // REQUIRES_NEW, on its own connection: this is the entry whose whole value is
            // that it exists after something went wrong.
            audit.recordSurvivingRollback(firmId, AuditService.Event.human(
                    "sam@northstar", "PREPARER", "FORCE_TRANSITION_REFUSED",
                    "FILING", "y", Map.of("reason", "filing is sealed")));
            throw new IllegalStateException("refused");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(verify().events()).isEqualTo(before + 1);
        assertThat(inFirmRead(() -> jdbc.queryForObject(
                "select count(*) from app.audit_event where action = 'FORCE_TRANSITION_REFUSED'",
                Long.class))).isPositive();
    }

    // =================================================================================
    // Append-only is structural
    // =================================================================================

    @Test
    @DisplayName("the runtime role cannot update or delete an audit event")
    void theLogIsAppendOnlyForTheApplicationRole() {
        inFirmWrite(() -> {
            audit.record(AuditService.Event.system("test", "IMMUTABLE", "X", "1", Map.of()));
            return null;
        });

        // Three independent mechanisms say no: the grant is revoked, there is no UPDATE or
        // DELETE policy (so under RLS a command with no permissive policy matches zero rows),
        // and a trigger refuses outright. Any one failing still leaves two.
        assertThatThrownBy(() -> inFirmWrite(() ->
                jdbc.update("update app.audit_event set actor = 'someone else'")))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> inFirmWrite(() ->
                jdbc.update("delete from app.audit_event")))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> inFirmWrite(() ->
                jdbc.update("delete from app.audit_chain_head")))
                .isInstanceOf(DataAccessException.class);
    }

    // =================================================================================
    // Tampering is detectable -- proved by actually tampering
    // =================================================================================

    @Test
    @DisplayName("editing an event's content breaks verification at that sequence number")
    void anEditedRowIsDetected() {
        inFirmWrite(() -> {
            audit.record(AuditService.Event.system("test", "TAMPER_EDIT" + tag, "X", "1", Map.of()));
            return null;
        });

        assertThat(verify().intact()).isTrue();

        // Only the OWNER can do this, and only by defeating the trigger -- which is the
        // realistic threat model: someone with enough privilege to edit the table directly.
        // The chain's job is to make that visible afterwards, not to prevent it.
        asOwnerBypassingTheTrigger("""
                update app.audit_event set actor = 'mallory'
                 where action = 'TAMPER_EDIT%s'
                """.formatted(tag));

        AuditService.Verification result = verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("altered content"));

        // Put it back so the chain is intact for whatever runs next.
        asOwnerBypassingTheTrigger("""
                update app.audit_event set actor = 'system:test'
                 where action = 'TAMPER_EDIT%s'
                """.formatted(tag));
        assertThat(verify().intact()).isTrue();
    }

    @Test
    @DisplayName("removing an event from the middle breaks the link and shows as a gap")
    void aDeletedRowIsDetected() {
        inFirmWrite(() -> {
            audit.record(AuditService.Event.system("test", "TAMPER_KEEP_A" + tag, "X", "1", Map.of()));
            audit.record(AuditService.Event.system("test", "TAMPER_DELETE" + tag, "X", "2", Map.of()));
            audit.record(AuditService.Event.system("test", "TAMPER_KEEP_B" + tag, "X", "3", Map.of()));
            return null;
        });
        assertThat(verify().intact()).isTrue();

        Map<String, Object> victim = inFirmRead(() -> jdbc.queryForMap("""
                select seq, prev_hash, hash from app.audit_event where action = 'TAMPER_DELETE%s'
                """.formatted(tag)));

        asOwnerBypassingTheTrigger(
                "delete from app.audit_event where action = 'TAMPER_DELETE%s'".formatted(tag));

        AuditService.Verification result = verify();
        assertThat(result.intact()).isFalse();
        // Two independent signals: the sequence has a hole, and the following event's
        // prev_hash no longer matches its predecessor. Either alone would be enough; having
        // both means a forger has to repair the whole tail, not just one row.
        assertThat(result.problems()).anyMatch(p -> p.contains("sequence gap"));
        assertThat(result.problems()).anyMatch(p -> p.contains("broken link"));

        // Restore, so the chain verifies again for subsequent tests.
        asOwnerBypassingTheTrigger("""
                insert into app.audit_event
                    (firm_id, seq, actor, action, entity_type, entity_id, detail, prev_hash, hash)
                values (%d, %s, 'system:test', 'TAMPER_DELETE%s', 'X', '2', '{}'::jsonb,
                        '\\x%s'::bytea, '\\x%s'::bytea)
                """.formatted(firmId, victim.get("seq"), tag,
                        hex((byte[]) victim.get("prev_hash")), hex((byte[]) victim.get("hash"))));
        assertThat(verify().intact()).isTrue();
    }

    @Test
    @DisplayName("truncating the tail is caught by the head, which the links alone cannot catch")
    void aTruncatedChainIsDetected() {
        inFirmWrite(() -> {
            audit.record(AuditService.Event.system("test", "TAMPER_TAIL" + tag, "X", "9", Map.of()));
            return null;
        });
        assertThat(verify().intact()).isTrue();

        Map<String, Object> last = inFirmRead(() -> jdbc.queryForMap("""
                select seq, prev_hash, hash from app.audit_event where action = 'TAMPER_TAIL%s'
                """.formatted(tag)));

        asOwnerBypassingTheTrigger(
                "delete from app.audit_event where action = 'TAMPER_TAIL%s'".formatted(tag));

        // Every REMAINING link is still valid -- deleting the last row breaks nothing that
        // the link check can see. Only the head knows how long the chain was meant to be,
        // which is exactly why the head is stored separately and pinned outside the database.
        AuditService.Verification result = verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("truncated"));

        asOwnerBypassingTheTrigger("""
                insert into app.audit_event
                    (firm_id, seq, actor, action, entity_type, entity_id, detail, prev_hash, hash)
                values (%d, %s, 'system:test', 'TAMPER_TAIL%s', 'X', '9', '{}'::jsonb,
                        '\\x%s'::bytea, '\\x%s'::bytea)
                """.formatted(firmId, last.get("seq"), tag,
                        hex((byte[]) last.get("prev_hash")), hex((byte[]) last.get("hash"))));
        assertThat(verify().intact()).isTrue();
    }

    // =================================================================================
    // Isolation
    // =================================================================================

    @Test
    @DisplayName("one firm's audit log is invisible to another")
    void auditIsFirmScopedLikeEverythingElse() {
        long other = inSystem(() -> jdbc.queryForObject(
                "select id from app.firm where slug = 'harborline'", Long.class));

        inFirmWrite(() -> {
            audit.record(AuditService.Event.system("test", "PRIVATE_TO_FIRM_ONE", "X", "1", Map.of()));
            return null;
        });

        Long visibleElsewhere = FirmContext.runAs(other, () -> transaction("test:audit-read", true,
                () -> jdbc.queryForObject(
                        "select count(*) from app.audit_event where action = 'PRIVATE_TO_FIRM_ONE'",
                        Long.class)));

        // No WHERE clause on firm_id anywhere in that query. The isolation is the policy.
        assertThat(visibleElsewhere).isZero();
    }

    // =================================================================================
    // Fixtures
    // =================================================================================

    private AuditService.Verification verify() {
        return FirmContext.runAs(firmId, () -> transaction("test:audit-verify", true,
                () -> audit.verify(firmId)));
    }

    /**
     * Tampers as the table owner, with the append-only trigger temporarily dropped.
     *
     * <p>This is not a hole in the design; it is the threat model the chain exists for.
     * The grants, the missing policies and the trigger stop the <em>application</em>. Someone
     * with owner privileges can always edit the table &mdash; and the point of hashing is
     * that doing so leaves evidence. A test that could not reach this state could not
     * demonstrate the property.
     */
    private void asOwnerBypassingTheTrigger(String sql) {
        try (var connection = ownerDataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("drop trigger if exists audit_event_append_only on app.audit_event");
            statement.execute("drop trigger if exists audit_chain_head_no_delete on app.audit_chain_head");
            statement.execute("select set_config('app.current_firm_id', '" + firmId + "', false)");
            statement.execute(sql);
            statement.execute("""
                    create trigger audit_event_append_only
                      before update or delete on app.audit_event
                      for each statement execute function app.deny_audit_mutation()
                    """);
            statement.execute("""
                    create trigger audit_chain_head_no_delete
                      before delete on app.audit_chain_head
                      for each statement execute function app.deny_chain_head_delete()
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("owner-level tampering fixture failed", e);
        }
    }

    private javax.sql.DataSource ownerDataSource() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        source.setUrl(System.getProperty("audit.owner.url",
                "jdbc:postgresql://localhost:5432/readiness_test"));
        source.setUsername("readiness_owner");
        source.setPassword(System.getenv().getOrDefault("OWNER_DB_PASSWORD", "readiness_owner_dev"));
        return source;
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private <T> T inFirmWrite(Supplier<T> body) {
        return FirmContext.runAs(firmId, () -> transaction("test:audit-write", false, body));
    }

    private <T> T inFirmRead(Supplier<T> body) {
        return FirmContext.runAs(firmId, () -> transaction("test:audit-read", true, body));
    }

    private <T> T inSystem(Supplier<T> body) {
        return transaction("system:test-firm-lookup", true, body);
    }

    private <T> T transaction(String name, boolean readOnly, Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setReadOnly(readOnly);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
