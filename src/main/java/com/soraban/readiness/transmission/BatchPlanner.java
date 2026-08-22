package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.transmission.domain.BatchState;
import com.soraban.readiness.transmission.domain.FilingState;
import com.soraban.readiness.transmission.domain.IdempotencyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T1 &mdash; plan and seal a batch.
 *
 * <p>One transaction that selects up to 100 eligible filings for a single client, computes
 * the idempotency key from their frozen content, writes the batch and its membership, and
 * moves the filings to {@link FilingState#BATCHED}. Nothing leaves the process.
 *
 * <h2>Three independent layers prevent a double-plan</h2>
 *
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE SKIP LOCKED} means two concurrent planners never see the
 *       same filing rows at all.</li>
 *   <li>If one somehow slipped past, {@code uq_one_submission_per_epoch} on the member table
 *       rejects the insert &mdash; a filing may be submitted at most once per attempt epoch,
 *       ever.</li>
 *   <li>If membership were identical, {@code unique (firm_id, idempotency_key)} rejects the
 *       batch itself &mdash; because the key is content-derived, two planners choosing the
 *       same filings compute the <em>same</em> key rather than two different ones.</li>
 * </ol>
 *
 * <p>That third layer is the argument for content-derived keys over random ones. With a
 * random key a double-plan produces two distinct keys and two live submissions, and nothing
 * in the database can tell them apart.
 *
 * <h2>The reconciliation gate</h2>
 *
 * <p>Planning refuses to run while any batch for this firm is awaiting reconciliation. The
 * reason is the rate budget rather than correctness: twenty calls a minute is scarce, and if
 * new submissions get in first, ambiguous batches stay ambiguous for minutes while the budget
 * is spent on work that is not blocked &mdash; and the morning-after page is wrong the whole
 * time. It also closes the one scenario in which a filing that is genuinely live could look
 * eligible again.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class BatchPlanner {

    private static final Logger log = LoggerFactory.getLogger(BatchPlanner.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final CrashHooks crashHooks;
    private final int maxBatchSize;

    public BatchPlanner(JdbcTemplate jdbc,
                        PlatformTransactionManager transactionManager,
                        CrashHooks crashHooks,
                        @org.springframework.beans.factory.annotation.Value("${irs.submit.max-filings-per-batch:100}")
                        int maxBatchSize) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.crashHooks = crashHooks;
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * Seals one batch for one client, if there is anything to send.
     *
     * @return the new batch id, or empty when nothing was eligible
     */
    public Optional<UUID> planOne(long clientId, int taxYear) {
        Optional<UUID> sealed =
                inTransaction("transmission:plan-batch", () -> planOneInTransaction(clientId, taxYear));

        // Fired AFTER the transaction commits, which is the whole meaning of this crash
        // point: the batch is durably SEALED and a crash here must leave it recoverable.
        // Firing it inside the transaction would roll the seal back and the test would be
        // asserting against a state the name explicitly denies.
        if (sealed.isPresent()) {
            crashHooks.reached(CrashHooks.CrashPoint.AFTER_SEAL_COMMIT);
        }
        return sealed;
    }

    private Optional<UUID> planOneInTransaction(long clientId, int taxYear) {
        if (reconciliationPending()) {
            log.debug("planning gated: reconciliation still pending for this firm");
            return Optional.empty();
        }

        // SKIP LOCKED so concurrent planners take disjoint work rather than blocking.
        // ORDER BY id makes selection deterministic, which matters because the key is derived
        // from membership: two planners given the same eligible set should agree.
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select id, generation, content_hash
                  from app.filing
                 where client_id = ? and tax_year = ? and state = ?
                 order by id
                 limit ?
                 for update skip locked
                """, clientId, taxYear, FilingState.READY_TO_TRANSMIT.name(), maxBatchSize);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // NOTE: SKIP LOCKED with LIMIT may legitimately return fewer than requested. The
        // batch is whatever came back -- never assume a full 100.
        List<IdempotencyKey.Member> members = new ArrayList<>(candidates.size());
        for (Map<String, Object> row : candidates) {
            members.add(new IdempotencyKey.Member(
                    (UUID) row.get("id"),
                    ((Number) row.get("generation")).intValue(),
                    (byte[]) row.get("content_hash")));
        }

        long firmId = FirmContext.require();
        String idempotencyKey = IdempotencyKey.batchKey(firmId, clientId, taxYear, members);
        UUID batchId = UUID.randomUUID();

        crashHooks.reached(CrashHooks.CrashPoint.BEFORE_SEAL_COMMIT);

        try {
            jdbc.update("""
                    insert into app.filing_batch (
                        id, firm_id, client_id, tax_year, idempotency_key, state,
                        filing_count, next_action_at)
                    values (?, app.current_firm_id(), ?, ?, ?, ?, ?, clock_timestamp())
                    """,
                    batchId, clientId, taxYear, idempotencyKey,
                    BatchState.SEALED.name(), members.size());

            List<Object[]> memberRows = members.stream()
                    .map(m -> new Object[]{batchId, m.filingId(), m.generation(), m.contentHash()})
                    .toList();

            jdbc.batchUpdate("""
                    insert into app.filing_batch_member (
                        firm_id, batch_id, filing_id, filing_generation, content_hash)
                    values (app.current_firm_id(), ?, ?, ?, ?)
                    """, memberRows);

            jdbc.update("""
                    update app.filing
                       set state = ?, state_changed_at = clock_timestamp()
                     where id = any (?)
                    """, FilingState.BATCHED.name(),
                    members.stream().map(IdempotencyKey.Member::filingId).toArray(UUID[]::new));

        } catch (DuplicateKeyException e) {
            // Either uq_one_submission_per_epoch or the batch key constraint fired. Both mean
            // "someone else already planned exactly this", which is a benign race rather than
            // an error -- and the transaction rolling back is precisely the right outcome.
            log.info("batch planning collided on an existing key or membership; rolling back "
                     + "(client={}, key={})", clientId, idempotencyKey);
            throw e;
        }

        log.debug("sealed batch {} client={} filings={} key={}",
                batchId, clientId, members.size(), idempotencyKey);

        return Optional.of(batchId);
    }

    /**
     * Seals batches across every client with eligible filings, until nothing is left.
     *
     * <p>Batching is per-client because the endpoint requires it, and that constraint is what
     * actually limits throughput: a client with six vendors still consumes a whole submission
     * call. 500 clients averaging six vendors is ~500 calls regardless of filing count, which
     * at 20 calls/minute is ~25 minutes of pure budget. That arithmetic, not row count, is
     * what would take this system down on February 1.
     */
    public int planAll(int taxYear) {
        // Read the client list in its own transaction, then plan each client in its own.
        // Deliberately NOT one long transaction: a single failure would otherwise roll back
        // every batch sealed so far, and sealing is exactly the work we want to keep.
        List<Long> clients = inTransaction("transmission:list-planning-clients",
                () -> jdbc.queryForList("""
                        select distinct client_id
                          from app.filing
                         where tax_year = ? and state = ?
                         order by client_id
                        """, Long.class, taxYear, FilingState.READY_TO_TRANSMIT.name()));

        int sealed = 0;
        for (Long clientId : clients) {
            // Loop per client: a client with 250 filings needs three batches.
            while (true) {
                Optional<UUID> batch;
                try {
                    batch = planOne(clientId, taxYear);
                } catch (DuplicateKeyException e) {
                    break;   // someone else is planning this client; leave it to them
                }
                if (batch.isEmpty()) {
                    break;
                }
                sealed++;
            }
        }

        log.info("phase=PLAN_BATCHES tax_year={} clients={} batches_sealed={}",
                taxYear, clients.size(), sealed);
        return sealed;
    }

    /**
     * Explicit transaction boundaries rather than {@code @Transactional}.
     *
     * <p>{@code planOne} is called both externally and from {@code planAll} on this same
     * instance. Spring applies {@code @Transactional} through a proxy, so the internal call
     * would bypass it entirely and run with no transaction at all -- which means no
     * {@code set_config}, which means every query raises {@code 28000} the moment RLS
     * evaluates {@code app.current_firm_id()}. Being explicit removes the question.
     */
    private <T> T inTransaction(String name, java.util.function.Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }

    /** True while any batch for this firm still has an unknown fate. */
    private boolean reconciliationPending() {
        Boolean pending = jdbc.queryForObject(
                "select exists (select 1 from app.filing_batch where needs_reconcile)",
                Boolean.class);
        return Boolean.TRUE.equals(pending);
    }
}
