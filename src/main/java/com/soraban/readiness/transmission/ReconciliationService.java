package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.transmission.domain.AttentionType;
import com.soraban.readiness.transmission.domain.FilingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves every batch whose fate is unknown, before anything new is submitted.
 *
 * <h2>What "unknown" means, precisely</h2>
 *
 * <p>A batch in {@code DISPATCHED} is one where a request left this process and we never
 * learned what the server did. That covers four situations which are, at the moment of
 * recovery, <b>indistinguishable in the durable state</b>:
 *
 * <ul>
 *   <li>the process died before the socket write &mdash; the IRS has nothing;</li>
 *   <li>the request was sent and the response was lost &mdash; the IRS has everything;</li>
 *   <li>failure mode B fired &mdash; the IRS has everything and told us it did not;</li>
 *   <li>the process was killed mid-call &mdash; either.</li>
 * </ul>
 *
 * <p>The design does not try to tell them apart, because it cannot. It asks the only party
 * that knows.
 *
 * <h2>Why this gates the planner</h2>
 *
 * <p>The reason is the rate budget rather than correctness. Twenty calls a minute is scarce;
 * if new submissions get in first, ambiguous batches stay ambiguous for minutes while the
 * budget is spent on work that is not blocked &mdash; and the morning-after page is wrong
 * for the whole of that time, which is precisely when someone is reading it.
 *
 * <p>It also closes the one scenario in which a filing that is genuinely live at the IRS
 * could look eligible for a fresh batch. That would take a bug to reach, but the barrier
 * costs nothing and removes the possibility.
 *
 * <h2>Two strategies</h2>
 *
 * <p>{@code REDISPATCH_SAME_KEY} (default) simply re-POSTs under the identical key. It costs
 * one token &mdash; the same as a status call &mdash; and a receipt resolves the batch
 * immediately, whereas a status call returning {@code Pending} would still need a follow-up.
 * Strictly cheaper, and equally safe <em>given a server that honours idempotency keys</em>.
 *
 * <p>{@code STATUS_FIRST} is used when attempts are exhausted, or when the endpoint is not
 * known to deduplicate. Its existence is the reason this design does not <em>depend</em> on
 * server-side idempotency: a non-deduping endpoint is survivable, and there is a test that
 * proves it with {@code idempotent-replay=false}.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final BatchDispatcher dispatcher;
    private final AckPoller poller;
    private final AttentionService attention;
    private final String strategy;
    private final int maxAttempts;

    public ReconciliationService(JdbcTemplate jdbc,
                                 PlatformTransactionManager transactionManager,
                                 BatchDispatcher dispatcher,
                                 AckPoller poller,
                                 AttentionService attention,
                                 @org.springframework.beans.factory.annotation.Value("${irs.reconcile-strategy:REDISPATCH_SAME_KEY}")
                                 String strategy,
                                 @org.springframework.beans.factory.annotation.Value("${irs.max-attempts:5}")
                                 int maxAttempts) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.dispatcher = dispatcher;
        this.poller = poller;
        this.attention = attention;
        this.strategy = strategy;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param flagged   batches whose fate was unknown at startup
     * @param resolved  batches settled by this pass
     * @param orphans   filings claiming membership in a batch that does not exist
     */
    public record ReconcileResult(int flagged, int resolved, int orphans) {
    }

    /**
     * Step 0, run before any worker starts: mark every ambiguous batch and drop stale leases.
     *
     * <p>Separate from the resolving pass because it must complete for <em>all</em> firms
     * before <em>any</em> planner runs. A lease held by a process that no longer exists must
     * not pin work forever, and clearing leases is safe because leases are liveness, never
     * correctness: two workers dispatching the same batch is harmless (same key), and the
     * thing that would <em>not</em> be harmless &mdash; two planners selecting the same
     * filings &mdash; is prevented by {@code SKIP LOCKED} plus
     * {@code uq_one_submission_per_epoch}, not by leases.
     */
    public int flagAmbiguousBatches() {
        Integer flagged = inTransaction("transmission:flag-ambiguous", () -> jdbc.update("""
                update app.filing_batch
                   set needs_reconcile = true, lease_owner = null, lease_expires_at = null,
                       next_action_at = clock_timestamp()
                 where state = 'DISPATCHED'
                """));
        return flagged == null ? 0 : flagged;
    }

    /** Resolves every flagged batch for one firm. */
    public ReconcileResult reconcile(long firmId) {
        return FirmContext.runAs(firmId, () -> {
            List<Map<String, Object>> ambiguous = inTransaction("transmission:list-ambiguous",
                    () -> jdbc.queryForList("""
                            select id, idempotency_key, attempt_count, client_id
                              from app.filing_batch
                             where needs_reconcile
                             order by idempotency_key
                            """));

            int resolved = 0;
            for (Map<String, Object> batch : ambiguous) {
                UUID batchId = (UUID) batch.get("id");
                int attempts = ((Number) batch.get("attempt_count")).intValue();

                boolean useStatus = "STATUS_FIRST".equals(strategy) || attempts >= maxAttempts;

                boolean acted = useStatus
                        ? poller.poll(firmId, batchId)
                        : dispatcher.dispatch(firmId, batchId);

                if (acted) {
                    resolved++;
                }
            }

            int orphans = sweepOrphans();
            clearFlags();

            log.info("phase=RECONCILE firm={} ambiguous={} acted={} orphans={} strategy={}",
                    firmId, ambiguous.size(), resolved, orphans, strategy);

            return new ReconcileResult(ambiguous.size(), resolved, orphans);
        });
    }

    /**
     * Finds filings that believe they are in a batch that does not exist.
     *
     * <p>Structurally impossible given the foreign keys, which is exactly why it is worth
     * checking: if it ever returns a row, an invariant this design rests on has been broken
     * and I want to know immediately rather than discover it through a duplicate filing.
     * A cheap assertion against a case that should never happen is how you find out that it
     * can.
     */
    public int sweepOrphans() {
        return inTransaction("transmission:sweep-orphans", () -> {
        List<Map<String, Object>> orphans = jdbc.queryForList("""
                select f.id, f.client_id, f.state
                  from app.filing f
                 where f.state in ('BATCHED', 'SUBMITTED_UNACKNOWLEDGED')
                   and not exists (select 1 from app.filing_batch_member m where m.filing_id = f.id)
                """);

        for (Map<String, Object> orphan : orphans) {
            String filingId = orphan.get("id").toString();
            attention.raise(AttentionType.ORPHANED_BATCH_MEMBERSHIP,
                    "FILING", filingId,
                    ((Number) orphan.get("client_id")).longValue(),
                    Map.of("state", String.valueOf(orphan.get("state")),
                           "note", "filing claims batch membership that does not exist"));

            // Returned to eligibility only because no membership row exists, which means no
            // idempotency key was ever derived that included it -- so nothing was ever sent
            // on its behalf.
            jdbc.update("""
                    update app.filing
                       set state = ?, state_changed_at = clock_timestamp()
                     where id = ?::uuid
                    """, FilingState.READY_TO_TRANSMIT.name(), filingId);
        }
        return orphans.size();
        });
    }

    public void clearFlags() {
        inTransaction("transmission:clear-flags", () -> jdbc.update("""
                update app.filing_batch set needs_reconcile = false
                 where needs_reconcile and state <> 'DISPATCHED'
                """));
    }

    /** True while the planner must stay gated for this firm. */
    public boolean pending() {
        Boolean pending = inTransaction("transmission:reconcile-pending", () ->
                jdbc.queryForObject(
                        "select exists (select 1 from app.filing_batch where needs_reconcile)",
                        Boolean.class));
        return Boolean.TRUE.equals(pending);
    }

    /**
     * Explicit transaction boundaries rather than {@code @Transactional}.
     *
     * <p>Spring's annotation is applied by a proxy, so a call from one method of this class
     * to another on the same instance bypasses it entirely and the annotation becomes inert.
     * Several methods here are called both externally and internally, which makes that trap
     * unavoidable with annotations -- and the failure is silent, because the work simply runs
     * in whatever transaction the caller had, or none.
     *
     * <p>"Or none" is the dangerous half: without a transaction there is no
     * {@code set_config}, so every query raises {@code 28000} the moment RLS evaluates
     * {@code app.current_firm_id()}. Being explicit removes the whole question.
     */
    private <T> T inTransaction(String name, java.util.function.Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
