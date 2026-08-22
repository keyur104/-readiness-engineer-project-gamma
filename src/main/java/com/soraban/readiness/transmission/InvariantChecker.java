package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An executable definition of "the recovered state is right".
 *
 * <p>This is <b>production code, not test code</b>, and that is deliberate. It runs after
 * startup reconciliation and on demand via {@code verify-invariants}, so the same assertions
 * that prove the tests also monitor the running system. An invariant that only holds in a
 * test is an invariant nobody is actually checking.
 *
 * <p>It is also the honest answer to the write-up's question <em>"how did you convince
 * yourself this is correct when it gets interrupted?"</em> &mdash; not "the tests pass", but
 * "here are the properties that must hold, here is the code that checks them, and it runs
 * against any database state you care to point it at."
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class InvariantChecker {

    private static final Logger log = LoggerFactory.getLogger(InvariantChecker.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final int rateLimit;
    private final java.time.Duration rateWindow;

    public InvariantChecker(JdbcTemplate jdbc,
                            PlatformTransactionManager transactionManager,
                            @org.springframework.beans.factory.annotation.Value("${irs.rate.limit:20}")
                            int rateLimit,
                            @org.springframework.beans.factory.annotation.Value("${irs.rate.window:60s}")
                            java.time.Duration rateWindow) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.rateLimit = rateLimit;
        this.rateWindow = rateWindow;
    }

    /**
     * @param id         short name, e.g. {@code I1}
     * @param name       what it asserts
     * @param violations rows that break it; empty means the invariant holds
     */
    public record Invariant(String id, String name, List<Map<String, Object>> violations) {

        public boolean holds() {
            return violations.isEmpty();
        }
    }

    public record Report(List<Invariant> invariants) {

        public boolean allHold() {
            return invariants.stream().allMatch(Invariant::holds);
        }

        public List<Invariant> failures() {
            return invariants.stream().filter(i -> !i.holds()).toList();
        }
    }

    public Report check(long firmId) {
        return FirmContext.runAs(firmId, () -> inTransaction("transmission:check-invariants", () -> {
            List<Invariant> results = new ArrayList<>();

            // -------------------------------------------------------------------------
            // I1 -- ZERO DUPLICATE FILINGS, judged against the IRS's own books.
            //
            // The single most important assertion in the project. Note it queries
            // irs_stub.recorded_filing, NOT our tables: checking our own beliefs would only
            // prove we are internally consistent. This asks what the endpoint actually holds.
            //
            // Scoped through irs_stub.submission.firm_id -- see the note on I2 for why every
            // stub-side query must carry that join explicitly.
            // -------------------------------------------------------------------------
            results.add(new Invariant("I1",
                    "no filing recorded twice at the IRS for the same generation",
                    jdbc.queryForList("""
                            select r.client_reference, r.filing_generation,
                                   count(*) as times_recorded
                              from irs_stub.recorded_filing r
                              join irs_stub.submission s on s.idempotency_key = r.idempotency_key
                             where s.firm_id = ?
                             group by r.client_reference, r.filing_generation
                            having count(*) > 1
                            """, firmId)));

            // -------------------------------------------------------------------------
            // I2 -- NOTHING IS LIVE THAT WE THINK WE NEVER SENT.
            //
            // The mode-B leak assertion, and the one to put on screen. If the IRS holds a
            // filing that never passed through SUBMITTED_UNACKNOWLEDGED, the write-ahead
            // barrier failed and we have shipped something we have no record of shipping.
            //
            // THE JOIN TO irs_stub.submission IS LOAD-BEARING, and its absence was a real
            // bug. The stub's schema is deliberately outside our tenancy model -- it stands
            // in for an external system -- so it carries no firm_id on recorded_filing and
            // no row-level security. app.filing, on the other side of the NOT EXISTS, is
            // fully scoped. Mixing the two means firm 2's check sees firm 1's recorded
            // filings, finds no matching firm-2 filing, and reports every one of them as a
            // leak: 692 false violations the first time both firms transmitted in the same
            // database.
            //
            // The lesson generalises past this query. Whenever an RLS-scoped table is joined
            // to an unscoped one, the scoping has to be restated by hand on the unscoped
            // side, because the mechanism that usually does it silently stops at the schema
            // boundary. That is the price of making the stub a genuinely separate system,
            // and it is worth paying -- but it has to be paid explicitly, every time.
            // -------------------------------------------------------------------------
            results.add(new Invariant("I2",
                    "nothing is recorded at the IRS that our system never marked as sent",
                    jdbc.queryForList("""
                            select r.client_reference, r.idempotency_key
                              from irs_stub.recorded_filing r
                              join irs_stub.submission s on s.idempotency_key = r.idempotency_key
                             where s.firm_id = ?
                               and not exists (
                                   select 1 from app.filing f
                                    where f.id::text = r.client_reference
                                      and f.state in ('SUBMITTED_UNACKNOWLEDGED', 'ACCEPTED', 'REJECTED'))
                            """, firmId)));

            // -------------------------------------------------------------------------
            // I3 -- WE NEVER CLAIM AN OUTCOME THE IRS NEVER GAVE US.
            //
            // The mirror of I2. A filing marked ACCEPTED that the endpoint has no record of
            // would mean we invented an outcome -- which would be worse than losing one,
            // because it looks settled.
            // -------------------------------------------------------------------------
            results.add(new Invariant("I3",
                    "no filing claims an outcome the IRS has no record of",
                    jdbc.queryForList("""
                            select f.id, f.state, f.irs_record_id
                              from app.filing f
                             where f.state in ('ACCEPTED', 'REJECTED')
                               and not exists (
                                   select 1
                                     from irs_stub.recorded_filing r
                                     join irs_stub.submission s
                                       on s.idempotency_key = r.idempotency_key
                                    where r.client_reference = f.id::text
                                      and s.firm_id = ?)
                            """, firmId)));

            // -------------------------------------------------------------------------
            // I4 -- NO FILING IS QUIETLY STUCK.
            //
            // Stronger than "the counts add up", because a count can reconcile perfectly
            // while a filing sits in a state nobody will ever act on. The formal property:
            //
            //   every filing is terminal, OR has scheduled work, OR has a human's name on it
            //
            // "We stopped retrying" satisfies the third disjunct -- which is precisely why
            // the brief insists it is not terminal.
            // -------------------------------------------------------------------------
            results.add(new Invariant("I4",
                    "every filing is terminal, scheduled, or flagged for a human",
                    jdbc.queryForList("""
                            select f.id, f.state
                              from app.filing f
                             where f.state <> 'ACCEPTED'
                               and not exists (
                                   select 1 from app.filing_batch_member m
                                     join app.filing_batch b on b.id = m.batch_id
                                    where m.filing_id = f.id
                                      and b.state in ('SEALED', 'DISPATCHED', 'SUBMITTED')
                                      and b.next_action_at is not null)
                               and not exists (
                                   select 1 from app.attention_item a
                                    where a.entity_type = 'FILING' and a.entity_id = f.id::text
                                      and a.resolved_at is null)
                               and f.state not in ('DRAFT', 'READY_TO_TRANSMIT')
                            """)));

            // -------------------------------------------------------------------------
            // I5 -- THE RATE BUDGET WAS NEVER EXCEEDED, per the endpoint's own log.
            //
            // Queried from irs_stub.call_log rather than our accounting, for the same reason
            // as I1: our own log would only prove our arithmetic is self-consistent.
            // -------------------------------------------------------------------------
            // Parameterised by the CONFIGURED budget rather than by literals.
            //
            // Hardcoding "20 calls per 60 seconds" made this invariant assert something the
            // system was not actually configured to do: the test profile shortens the window
            // to 500ms so runs finish quickly, and 21 calls in 60 real seconds is then
            // entirely correct. The invariant reported a violation that was not one.
            //
            // The same flaw would misreport in the other direction in production: raise
            // irs.rate.limit to 30 and this would keep asserting 20, quietly failing every
            // run. An invariant should express the policy, not a snapshot of it.
            results.add(new Invariant("I5",
                    "no rolling %s window contains more than %d calls for a firm"
                            .formatted(rateWindow, rateLimit),
                    jdbc.queryForList("""
                            select firm_id, window_start, calls from (
                              select firm_id, at as window_start,
                                     count(*) over (partition by firm_id order by at
                                       range between (? || ' milliseconds')::interval preceding
                                                 and current row) as calls
                                from irs_stub.call_log
                               where firm_id = ?) w
                             where calls > ?
                            """, rateWindow.toMillis(), firmId, rateLimit)));

            // -------------------------------------------------------------------------
            // I6 -- STRUCTURAL CONSISTENCY between batch state and member filing state.
            // A SEALED batch means nothing was sent, so none of its filings may believe
            // otherwise; a DISPATCHED batch means the opposite.
            // -------------------------------------------------------------------------
            results.add(new Invariant("I6",
                    "batch state and member filing states agree",
                    jdbc.queryForList("""
                            select b.id as batch_id, b.state as batch_state,
                                   f.id as filing_id, f.state as filing_state
                              from app.filing_batch b
                              join app.filing_batch_member m on m.batch_id = b.id
                              join app.filing f on f.id = m.filing_id
                             where (b.state = 'SEALED'
                                    and f.state = 'SUBMITTED_UNACKNOWLEDGED')
                                or (b.state in ('DISPATCHED', 'SUBMITTED')
                                    and f.state in ('READY_TO_TRANSMIT', 'BATCHED'))
                            """)));

            // -------------------------------------------------------------------------
            // I7 -- EVIDENCE EXISTS FOR EVERY CLAIM.
            // -------------------------------------------------------------------------
            results.add(new Invariant("I7",
                    "accepted filings have a record id; rejected filings have a reason",
                    jdbc.queryForList("""
                            select id, state, irs_record_id, reject_code
                              from app.filing
                             where (state = 'ACCEPTED' and irs_record_id is null)
                                or (state = 'REJECTED' and reject_code is null)
                            """)));

            // -------------------------------------------------------------------------
            // I8 -- BATCH SHAPE matches what the endpoint accepts.
            // -------------------------------------------------------------------------
            results.add(new Invariant("I8",
                    "no batch exceeds 100 filings or spans more than one client",
                    jdbc.queryForList("""
                            select b.id, b.filing_count, count(distinct f.client_id) as clients
                              from app.filing_batch b
                              join app.filing_batch_member m on m.batch_id = b.id
                              join app.filing f on f.id = m.filing_id
                             group by b.id, b.filing_count
                            having b.filing_count > 100 or count(distinct f.client_id) > 1
                            """)));

            Report report = new Report(results);
            if (report.allHold()) {
                log.info("phase=INVARIANTS firm={} checked={} status=ALL_HOLD", firmId, results.size());
            } else {
                for (Invariant failure : report.failures()) {
                    log.error("phase=INVARIANTS firm={} VIOLATED {} \"{}\" rows={}",
                            firmId, failure.id(), failure.name(), failure.violations().size());
                }
            }
            return report;
        }));
    }

    /**
     * One read-only transaction for the whole check.
     *
     * <p>Necessary for firm context, and desirable in its own right: all eight invariants see
     * a single consistent snapshot. Checking them across separate transactions could produce
     * a contradictory report during an active run -- I2 and I3 are complementary halves of
     * the same statement, and reading them at different instants could show both "failing"
     * purely because a batch settled in between.
     */
    private <T> T inTransaction(String name, java.util.function.Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definition.setReadOnly(true);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
