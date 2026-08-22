package com.soraban.readiness.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A single value summarising a firm's entire live ledger.
 *
 * <p>Exists to make <em>"importing the same file twice changes nothing"</em> checkable in
 * one number, rather than by comparing half a million rows.
 *
 * <h2>Why {@code bit_xor} rather than hashing a sorted concatenation</h2>
 *
 * <p>The obvious construction is {@code md5(string_agg(row_hash, '' ORDER BY id))}. It
 * works, but the {@code ORDER BY} forces a sort of every live row &mdash; hundreds of
 * megabytes of sorting purely to make the result order-independent.
 *
 * <p>XOR is commutative and associative, so {@code bit_xor} needs no ordering at all: it
 * is a single unordered aggregate over a sequential scan, and it parallelises. The
 * checksum of a book is the XOR of its rows regardless of the order they are read in,
 * which is exactly the property wanted.
 *
 * <p>The known weakness, stated rather than glossed: XOR is insensitive to a row appearing
 * an even number of times, so a checksum alone cannot distinguish "row X present once"
 * from "row X present twice, plus row X missing". That is why the checksum is one of
 * <b>four</b> independent idempotency signals, not the only one:
 *
 * <ol>
 *   <li>the delta counters on {@code import_run} are all zero;</li>
 *   <li>the row count is unchanged;</li>
 *   <li>this checksum is unchanged;</li>
 *   <li><b>the dirty set is empty</b> &mdash; strictly the strongest of the four, because
 *       it proves not merely that nothing changed but that nothing was even
 *       <em>considered</em> changed, so the downstream cost of a redundant import is
 *       provably zero.</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class BookChecksum {

    private final JdbcTemplate jdbc;

    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public BookChecksum(JdbcTemplate jdbc,
                        org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /**
     * @param liveRows     ledger lines not soft-deleted
     * @param deletedRows  soft-deleted lines, counted separately so a tombstone is visible
     *                     as a change rather than hidden by simply vanishing from the sum
     * @param checksum     order-independent XOR fold over every live row's content hash
     * @param vendorCount  distinct vendors
     * @param dirtyClients pending re-determination units
     */
    public record Snapshot(long liveRows, long deletedRows, long checksum,
                           long vendorCount, long dirtyClients) {

        /** Hex, for a log line or a console table. */
        public String checksumHex() {
            return "%016x".formatted(checksum);
        }

        /** True when nothing at all is pending re-determination. */
        public boolean isSettled() {
            return dirtyClients == 0;
        }
    }

    /**
     * Snapshots the current firm's book.
     *
     * <p><b>Firm context must already be established by the caller</b>, via
     * {@code FirmContext.runAs(...)} wrapping the call. It cannot be set inside this method:
     * {@code @Transactional} starts the transaction before the body runs, and
     * {@code FirmTransactionManager} reads the context at transaction start. Setting it
     * afterwards would be too late, and the transaction would be rejected for having no firm.
     *
     * <p>The ordering is worth internalising because getting it backwards fails in a way
     * that is initially confusing but is actually the design working: without a transaction
     * there is no {@code set_config}, so {@code app.current_firm_id()} raises {@code 28000}
     * rather than quietly returning someone else's rows.
     */
    public Snapshot snapshot() {
        return inTransaction("ingest:book-snapshot", true, () -> jdbc.queryForObject("""
                select
                  (select count(*) from app.ledger_line where deleted_at is null)     as live_rows,
                  (select count(*) from app.ledger_line where deleted_at is not null) as deleted_rows,
                  coalesce((
                    select bit_xor(
                             ('x' || encode(substring(row_hash from 1 for 8), 'hex'))::bit(64)::bigint)
                      from app.ledger_line
                     where deleted_at is null), 0)                                    as checksum,
                  (select count(*) from app.vendor)                                   as vendor_count,
                  (select count(*) from app.determination_dirty_client)               as dirty_clients
                """,
                (rs, rowNum) -> new Snapshot(
                        rs.getLong("live_rows"),
                        rs.getLong("deleted_rows"),
                        rs.getLong("checksum"),
                        rs.getLong("vendor_count"),
                        rs.getLong("dirty_clients"))));
    }

    /**
     * Clears pending dirty marks for the current firm.
     *
     * <p>Used by {@code verify-import} so the "dirty set is empty afterwards" assertion is
     * about the import under test rather than about whatever ran before it. Firm context
     * must already be established by the caller, for the reason given on {@link #snapshot()}.
     */
    public int clearDirtyMarks() {
        return inTransaction("ingest:clear-dirty-marks", false, () ->
                jdbc.update("delete from app.determination_dirty_client"));
    }

    /**
     * Explicit boundary, rather than {@code @Transactional}.
     *
     * <p>The annotation works through a proxy, so it is silently inert on a self-invocation --
     * and it cannot set a transaction NAME, which is what {@code FirmTransactionManager} reads
     * to decide whether to stamp {@code app.current_firm_id}. Both properties are load-bearing
     * here, so the boundary is written out. {@code ArchitectureTest} keeps it that way.
     */
    private <T> T inTransaction(String name, boolean readOnly, java.util.function.Supplier<T> body) {
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setName(name);
        definition.setReadOnly(readOnly);
        return new org.springframework.transaction.support.TransactionTemplate(
                transactionManager, definition).execute(status -> body.get());
    }

}
