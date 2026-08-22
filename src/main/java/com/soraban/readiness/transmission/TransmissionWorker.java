package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.transmission.ratelimit.FirmRateBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives one firm's transmission work: claim the most urgent due batch, act on it, repeat.
 *
 * <h2>Priority under a scarce budget</h2>
 *
 * <p>Three demand classes compete for twenty calls a minute:
 *
 * <ol>
 *   <li><b>Reconciliation</b> &mdash; unbounded priority. A small, bounded set; it blocks the
 *       planner; and it is the only class where delay produces <em>incorrect information</em>
 *       rather than merely late information.</li>
 *   <li><b>Submissions</b> &mdash; and the argument is from the domain, not from taste:
 *       <blockquote>A filing sitting in {@code READY_TO_TRANSMIT} at 11:59 p.m. on
 *       February 2 is a <b>penalty</b>. A filing sitting in
 *       {@code SUBMITTED_UNACKNOWLEDGED} at 11:59 p.m. is <b>filed on time</b> --
 *       acknowledgment latency is the IRS's clock, not ours.</blockquote>
 *       Unsubmitted work is therefore strictly more urgent than unresolved work.</li>
 *   <li><b>Status polls</b>, with a <b>reserved floor</b>.</li>
 * </ol>
 *
 * <h2>Why polling gets a floor rather than losing outright</h2>
 *
 * <p>Strict priority would starve it completely. A firm with 50,000 filings would submit for
 * hours and learn nothing; the morning-after page would be blank all night, which defeats the
 * entire purpose of Part 4. So polling is promoted above submissions whenever fewer than
 * {@code reserved-share} of the window's calls have been status calls.
 *
 * <p>The whole priority decision is made <b>in one query, atomically with the claim</b>, so
 * two workers cannot both decide they are the one allowed to poll.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class TransmissionWorker {

    private static final Logger log = LoggerFactory.getLogger(TransmissionWorker.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final BatchDispatcher dispatcher;
    private final AckPoller poller;
    private final AttentionService attention;
    private final FirmRateBudget rateBudget;
    private final double reservedShare;
    private final Duration unackThreshold;

    /**
     * Whether an ambiguous batch may be re-sent under its existing key, or must be asked about
     * first.
     *
     * <p>Read here as well as in {@link ReconciliationService} because the setting has to
     * govern <b>both</b> paths, and originally it did not &mdash; see {@link #shouldAsk}.
     */
    private final boolean askBeforeResending;

    public TransmissionWorker(JdbcTemplate jdbc,
                              PlatformTransactionManager transactionManager,
                              BatchDispatcher dispatcher,
                              AckPoller poller,
                              AttentionService attention,
                              FirmRateBudget rateBudget,
                              @org.springframework.beans.factory.annotation.Value("${irs.poll.reserved-share:0.20}")
                              double reservedShare,
                              @org.springframework.beans.factory.annotation.Value("${irs.unack-threshold:30m}")
                              Duration unackThreshold,
                              @org.springframework.beans.factory.annotation.Value("${irs.reconcile-strategy:REDISPATCH_SAME_KEY}")
                              String reconcileStrategy) {
        this.askBeforeResending = "STATUS_FIRST".equals(reconcileStrategy);
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.dispatcher = dispatcher;
        this.poller = poller;
        this.attention = attention;
        this.rateBudget = rateBudget;
        this.reservedShare = reservedShare;
        this.unackThreshold = unackThreshold;
    }

    /** What the priority query selected. */
    private record Claim(UUID batchId, String state, boolean needsReconcile) {
    }

    /**
     * Poll, or (re-)dispatch?
     *
     * <p>A {@code SUBMITTED} batch holds a receipt, so there is a specific thing to ask about
     * and polling is the only sensible move.
     *
     * <p>A {@code DISPATCHED} batch is the interesting one: we committed the intent to send it
     * and never learned the outcome. Two moves are available, and which one is safe depends
     * entirely on the endpoint.
     *
     * <ul>
     *   <li><b>{@code REDISPATCH_SAME_KEY}</b> &mdash; re-send under the identical idempotency
     *       key. One call, and a receipt resolves it immediately. Safe <em>because the endpoint
     *       deduplicates</em>: it recognises the key and replays rather than recording again.</li>
     *   <li><b>{@code STATUS_FIRST}</b> &mdash; ask by key and send nothing. One call, and the
     *       answer is proof rather than a guess. The only safe move when the endpoint keeps no
     *       idempotency store.</li>
     * </ul>
     *
     * <p><b>This method previously returned {@code "SUBMITTED".equals(state)} and ignored the
     * strategy entirely</b>, so the retry path always re-dispatched. The setting governed
     * restart reconciliation and nothing else, which meant the system quietly depended on
     * server-side deduplication even when configured not to. Against a non-deduplicating
     * endpoint with failure mode B, {@code NonDedupingEndpointIT} produced <b>21 duplicate
     * filings</b> &mdash; the single outcome the brief forbids.
     *
     * <p>The rule the rest of the design already states is that only evidence moves a filing.
     * A blind re-send is not evidence; it is a bet that someone else's system will catch it.
     */
    private boolean shouldAsk(Claim claim) {
        if ("SUBMITTED".equals(claim.state())) {
            return true;
        }
        return askBeforeResending && "DISPATCHED".equals(claim.state());
    }

    /**
     * Runs until nothing is due or the budget is spent.
     *
     * @param maxCalls safety bound so a demo cannot run away; -1 for unbounded
     * @return calls actually made
     */
    public int drain(long firmId, int maxCalls) {
        return FirmContext.runAs(firmId, () -> {
            int calls = 0;

            while (maxCalls < 0 || calls < maxCalls) {
                int remaining = inTransaction("transmission:budget-check",
                        () -> rateBudget.remaining(firmId));
                if (remaining <= 0) {
                    log.debug("rate budget exhausted for firm {}; stopping this drain", firmId);
                    break;
                }

                Claim claim = claimNext(firmId);
                if (claim == null) {
                    break;
                }

                boolean acted = shouldAsk(claim)
                        ? poller.poll(firmId, claim.batchId())
                        : dispatcher.dispatch(firmId, claim.batchId());

                if (acted) {
                    calls++;
                }

                // Cheap and idempotent, so it runs every iteration rather than on a timer:
                // a long-running drain surfaces stuck batches as it goes instead of only
                // once it finishes.
                inTransaction("transmission:sweep-unacked",
                        () -> attention.sweepUnacknowledged(unackThreshold));
            }

            return calls;
        });
    }

    /**
     * Selects the single most urgent due batch, and claims it.
     *
     * <p>The {@code cls} column encodes the priority: 0 reconciliation, 1 submission
     * (or a promoted poll), 2 ordinary poll. Computing the promotion inside the same query
     * that reads the window count is what keeps the decision atomic.
     */
    private Claim claimNext(long firmId) {
        return inTransaction("transmission:claim-next", () -> claimNextInTransaction(firmId));
    }

    private Claim claimNextInTransaction(long firmId) {
        int reserved = (int) Math.ceil(rateBudget.limit() * reservedShare);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                with used as (
                  select count(*) filter (where call_type = 'STATUS') as polls
                    from app.irs_call_log
                   where called_at > clock_timestamp() - (? || ' milliseconds')::interval
                ),
                due as (
                  select id, state, needs_reconcile, next_action_at, 0 as cls
                    from app.filing_batch
                   where needs_reconcile
                     and next_action_at <= clock_timestamp()
                     and (lease_expires_at is null or lease_expires_at < clock_timestamp())

                  union all

                  select id, state, needs_reconcile, next_action_at, 1
                    from app.filing_batch
                   where state = 'SEALED'
                     and next_action_at <= clock_timestamp()
                     and (lease_expires_at is null or lease_expires_at < clock_timestamp())

                  union all

                  -- Promoted to priority 1 while the reserved floor is unmet, so polling
                  -- cannot be starved by a long submission backlog.
                  select id, state, needs_reconcile, next_action_at,
                         case when (select polls from used) < ? then 1 else 2 end
                    from app.filing_batch
                   where state in ('DISPATCHED', 'SUBMITTED')
                     and not needs_reconcile
                     and next_action_at <= clock_timestamp()
                     and (lease_expires_at is null or lease_expires_at < clock_timestamp())
                )
                select id, state, needs_reconcile
                  from due
                 order by cls, next_action_at
                 limit 1
                """, rateBudget.window().toMillis(), reserved);

        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        return new Claim((UUID) row.get("id"),
                (String) row.get("state"),
                Boolean.TRUE.equals(row.get("needs_reconcile")));
    }

    /**
     * Explicit transactions rather than {@code @Transactional}.
     *
     * <p>Every database touch in the drain loop needs one, because firm context is applied at
     * transaction start: without a transaction there is no {@code set_config}, and RLS raises
     * {@code 28000} on the first query. Annotations would not help here anyway, since
     * {@code claimNext} is called from {@code drain} on this same instance and Spring's proxy
     * is bypassed on self-invocation.
     *
     * <p>Note these are short and separate on purpose. The claim commits before the HTTP call
     * begins, so no pooled connection is held across the network.
     */
    private <T> T inTransaction(String name, java.util.function.Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
