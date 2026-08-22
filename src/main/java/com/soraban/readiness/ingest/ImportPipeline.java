package com.soraban.readiness.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.security.TinCryptoService;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;

/**
 * Imports one firm's export directory.
 *
 * <pre>
 *   file -> parse -> dialect map -> normalize -> validate
 *        |-- valid   -> COPY stream -> UNLOGGED per-run staging
 *        \-- invalid -> rejection sink (its own transaction)
 *        -> index staging -> dedupe staging
 *        -> upsert vendors
 *        -> INSERT ... ON CONFLICT DO UPDATE WHERE row_hash IS DISTINCT FROM
 *        -> tombstone anti-join
 *        -> (trigger fires) dirty marks
 *        -> finalise import_run
 * </pre>
 *
 * <h2>Restartability comes free from idempotency</h2>
 *
 * <p>There is deliberately no partial-resume machinery, no checkpointing, and no
 * per-file progress table. Because the merge is idempotent, recovery from a failure at
 * <em>any</em> point is simply "run it again": rows already merged compare equal on
 * {@code row_hash} and produce no writes, and the staging table is rebuilt from the file.
 *
 * <p>Designing the merge to be idempotent bought restartability without building it, which
 * is a better outcome than any amount of resume machinery.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class ImportPipeline {

    private static final Logger log = LoggerFactory.getLogger(ImportPipeline.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final TinCryptoService tinCrypto;
    private final ObjectMapper json = new ObjectMapper();
    private final double maxRejectionRate;

    public ImportPipeline(JdbcTemplate jdbc,
                          PlatformTransactionManager transactionManager,
                          TinCryptoService tinCrypto,
                          @org.springframework.beans.factory.annotation.Value("${readiness.import.max-rejection-rate:0.05}")
                          double maxRejectionRate) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.tinCrypto = tinCrypto;
        this.maxRejectionRate = maxRejectionRate;
    }

    /**
     * @param runId          the import_run row
     * @param rowsRead       data rows parsed across every file
     * @param rowsRejected   rows that could not be represented
     * @param rowsInserted   new ledger lines
     * @param rowsUpdated    existing lines whose content changed
     * @param rowsUnchanged  lines present in the file and identical to what we hold
     * @param rowsTombstoned lines absent from the declared scope and soft-deleted
     * @param duplicateKeys  rows collapsed by staging dedupe
     * @param dirtyClients   clients marked for re-determination
     */
    public record ImportResult(
            long runId, long rowsRead, long rowsRejected, long rowsInserted, long rowsUpdated,
            long rowsUnchanged, long rowsTombstoned, long duplicateKeys, long dirtyClients,
            long totalMs, Map<String, Long> phaseMs, Map<String, Long> rejectionsByReason
    ) {
    }

    // =================================================================================
    // Entry point
    // =================================================================================

    public ImportResult importExport(long firmId, Path exportDir) throws IOException {
        return FirmContext.runAs(firmId, () -> {
            try {
                return doImport(firmId, exportDir);
            } catch (IOException e) {
                throw new UncheckedIOWrapper(e);
            }
        });
    }

    private ImportResult doImport(long firmId, Path exportDir) throws IOException {
        long startedAt = System.nanoTime();
        Map<String, Long> phase = new LinkedHashMap<>();

        JsonNode manifest = json.readTree(exportDir.resolve("manifest.json").toFile());
        int taxYear = manifest.path("taxYear").asInt();
        int revision = manifest.path("revision").asInt();

        List<String> declaredClientRefs = new ArrayList<>();
        manifest.path("clientRefs").forEach(node -> declaredClientRefs.add(node.asText()));

        // The years this export is authoritative for. Deletion is scoped to them, so a row
        // the bookkeeper removed from an adjacent year is still removable -- and a later
        // export covering only one year cannot wipe another.
        List<Integer> coveredTaxYears = new ArrayList<>();
        manifest.path("coveredTaxYears").forEach(node -> coveredTaxYears.add(node.asInt()));
        if (coveredTaxYears.isEmpty()) {
            coveredTaxYears.add(taxYear);   // older manifests declared only the filing year
        }

        long runId = inTransaction("import:create-run", () -> createRun(exportDir, taxYear, revision));

        // Clients first: every payment row needs its client_id resolved before parsing, so
        // that the TIN encryption AAD can bind to it.
        long clientMs = time(() -> inTransaction("import:clients", () -> {
            upsertClients(exportDir, runId);
            return null;
        }));
        phase.put("clients_ms", clientMs);

        Map<String, Long> clientIdsByRef = inTransaction("import:load-clients", this::loadClientIds);

        String stagingTable = "ledger_line_" + runId;
        List<RejectionRecord> rejections = new ArrayList<>();
        long rowsRead;

        long parseCopyMs = System.nanoTime();
        rowsRead = inTransaction("import:copy", () -> {
            createStagingTable(stagingTable);
            return copyAllFiles(firmId, exportDir, manifest, clientIdsByRef, stagingTable, rejections);
        });
        phase.put("parse_copy_ms", (System.nanoTime() - parseCopyMs) / 1_000_000);

        // Rejections are written in their own transaction, BEFORE the merge, so the report
        // survives even if the merge aborts. If an import dies, that is exactly when you
        // most want to know which rows it choked on.
        long rejectMs = time(() -> inTransaction("import:rejections", () -> {
            writeRejections(runId, rejections);
            return null;
        }));
        phase.put("rejections_ms", rejectMs);

        // Per-row skipping is correct; wholesale skipping is not. A misdetected dialect
        // that quietly imports 3% of a file is a far worse outcome than a loud failure.
        if (rowsRead > 0 && (double) rejections.size() / rowsRead > maxRejectionRate) {
            inTransaction("import:fail", () -> {
                failRun(runId, "TOO_MANY_REJECTIONS: %d of %d rows (%.1f%%) exceeded the %.1f%% threshold"
                        .formatted(rejections.size(), rowsRead,
                                   100.0 * rejections.size() / rowsRead, 100.0 * maxRejectionRate));
                return null;
            });
            throw new IllegalStateException(
                    "import aborted: %d of %d rows rejected (%.1f%%), above the configured %.1f%% threshold"
                            .formatted(rejections.size(), rowsRead,
                                       100.0 * rejections.size() / rowsRead, 100.0 * maxRejectionRate));
        }

        MergeCounts counts = inTransaction("import:merge", () -> {
            // SET LOCAL is scoped to one transaction, and the merge runs in its own, so
            // the settings applied during COPY do not carry over here.
            jdbc.execute("set local synchronous_commit = off");

            // 512MB, not the 128MB used elsewhere, and the reason is the dirty-marking
            // trigger. A statement-level trigger with a transition table makes Postgres
            // materialise every affected row into a tuplestore -- roughly 100MB for a
            // 500k-row merge. Below that threshold the tuplestore spills to disk and the
            // trigger's cost roughly doubles. Measured: the trigger costs ~22s of the
            // merge, and most of that is spill, not the DISTINCT over the rows.
            jdbc.execute("set local work_mem = '512MB'");

            long t0 = System.nanoTime();
            long duplicates = dedupeStaging(stagingTable);
            phase.put("dedupe_ms", (System.nanoTime() - t0) / 1_000_000);

            long t1 = System.nanoTime();
            upsertVendors(stagingTable, runId);
            phase.put("vendor_ms", (System.nanoTime() - t1) / 1_000_000);

            long t2 = System.nanoTime();
            MergeCounts merged = mergeLedger(stagingTable, runId);
            phase.put("merge_ms", (System.nanoTime() - t2) / 1_000_000);

            long t3 = System.nanoTime();
            long tombstoned = tombstone(stagingTable, runId, coveredTaxYears, declaredClientRefs, manifest);
            phase.put("tombstone_ms", (System.nanoTime() - t3) / 1_000_000);

            return new MergeCounts(merged.inserted(), merged.updated(),
                                   merged.unchanged(), tombstoned, duplicates);
        });

        long dirtyClients = inTransaction("import:count-dirty", () ->
                jdbc.queryForObject("select count(*) from app.determination_dirty_client", Long.class));

        inTransaction("import:drop-staging", () -> {
            jdbc.execute("drop table if exists stg." + stagingTable);
            return null;
        });

        long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
        phase.put("total_ms", totalMs);

        Map<String, Long> byReason = new LinkedHashMap<>();
        for (RejectionRecord rejection : rejections) {
            byReason.merge(rejection.code().name(), 1L, Long::sum);
        }

        long unchanged = rowsRead - rejections.size() - counts.inserted() - counts.updated() - counts.duplicates();
        inTransaction("import:finalise", () -> {
            finaliseRun(runId, rowsRead, rejections.size(), counts, Math.max(0, unchanged), phase, byReason);
            return null;
        });

        log.info("phase=IMPORT run={} rows={} rejected={} inserted={} updated={} unchanged={} "
                 + "tombstoned={} duplicates={} dirty_clients={} ms={} sla=120000 {}",
                runId, rowsRead, rejections.size(), counts.inserted(), counts.updated(),
                Math.max(0, unchanged), counts.tombstoned(), counts.duplicates(), dirtyClients,
                totalMs, totalMs <= 120_000 ? "OK" : "SLA_MISSED");

        return new ImportResult(runId, rowsRead, rejections.size(), counts.inserted(), counts.updated(),
                Math.max(0, unchanged), counts.tombstoned(), counts.duplicates(), dirtyClients,
                totalMs, phase, byReason);
    }

    // =================================================================================
    // Parse and COPY
    // =================================================================================

    private long copyAllFiles(long firmId, Path exportDir, JsonNode manifest,
                              Map<String, Long> clientIdsByRef, String stagingTable,
                              List<RejectionRecord> rejections) {
        long rowsRead = 0;
        for (JsonNode file : manifest.path("files")) {
            String name = file.path("name").asText();
            String declaredSystem = file.path("sourceSystem").asText();
            rowsRead += copyFile(firmId, exportDir.resolve(name), name, declaredSystem,
                                 clientIdsByRef, stagingTable, rejections);
        }
        return rowsRead;
    }

    private long copyFile(long firmId, Path path, String fileName, String declaredSystem,
                          Map<String, Long> clientIdsByRef, String stagingTable,
                          List<RejectionRecord> rejections) {
        try (InputStream raw = Files.newInputStream(path);
             InputStream in = fileName.endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20)) {

            CsvParser parser = new CsvParser(parserSettings());
            parser.beginParsing(reader);

            String[] header = parser.parseNext();
            if (header == null) {
                return 0;
            }

            SourceDialect dialect = SourceDialect.detect(List.of(header), declaredSystem);
            Map<String, Integer> columnIndex = dialect.columnIndex(List.of(header));

            RowNormalizer normalizer = new RowNormalizer(
                    firmId, dialect, declaredSystem, clientIdsByRef, tinCrypto, columnIndex);

            NormalizingIterator rows = new NormalizingIterator(
                    parser, normalizer, fileName, rejections);

            CopyRowReader copyReader = new CopyRowReader(rows);

            Connection connection = DataSourceUtils.getConnection(jdbc.getDataSource());
            PGConnection pg = connection.unwrap(PGConnection.class);
            String sql = "copy stg." + stagingTable + " (" + CopyRowReader.COPY_COLUMNS
                       + ") from stdin (format csv)";
            pg.getCopyAPI().copyIn(sql, copyReader);

            log.info("phase=copy file={} dialect={} rows={} rejected_so_far={}",
                    fileName, dialect.id(), copyReader.rowsRendered(), rejections.size());

            return rows.rowsSeen();

        } catch (Exception e) {
            throw new IllegalStateException("failed importing " + fileName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Adapts the CSV parser to an iterator of valid rows, diverting rejections as it goes.
     *
     * <p>This is where the two outputs of the parse phase separate: valid rows continue
     * into the COPY stream, invalid ones land in the rejection list and never reach it. The
     * iterator must skip ahead past rejections during {@code hasNext()}, because
     * {@code COPY} has no way to express "no row here".
     */
    private static final class NormalizingIterator implements Iterator<NormalizedRow> {

        private final CsvParser parser;
        private final RowNormalizer normalizer;
        private final String fileName;
        private final List<RejectionRecord> rejections;

        private NormalizedRow next;
        private long lineNo = 1;   // line 1 is the header
        private long rowsSeen;

        NormalizingIterator(CsvParser parser, RowNormalizer normalizer, String fileName,
                            List<RejectionRecord> rejections) {
            this.parser = parser;
            this.normalizer = normalizer;
            this.fileName = fileName;
            this.rejections = rejections;
        }

        @Override
        public boolean hasNext() {
            while (next == null) {
                String[] fields;
                try {
                    fields = parser.parseNext();
                } catch (com.univocity.parsers.common.TextParsingException e) {
                    // The parser could not produce a record at all. With
                    // STOP_AT_DELIMITER this should be unreachable, but "should be
                    // unreachable" is not a guarantee to bet an overnight run on: a
                    // malformed file must never be able to abort the import. Record it and
                    // keep going.
                    lineNo++;
                    rowsSeen++;
                    rejections.add(new RejectionRecord(
                            fileName, lineNo, RejectionCode.RAGGED_ROW,
                            "parser could not read the record", ""));
                    continue;
                }
                if (fields == null) {
                    return false;
                }
                lineNo++;
                rowsSeen++;

                RowNormalizer.Result result = normalizer.normalize(fields, lineNo);
                if (result.isRejected()) {
                    rejections.add(new RejectionRecord(
                            fileName, lineNo, result.rejection(), result.detail(),
                            redactedLine(fields)));
                } else {
                    next = result.row();
                }
            }
            return true;
        }

        @Override
        public NormalizedRow next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            NormalizedRow row = next;
            next = null;
            return row;
        }

        long rowsSeen() {
            return rowsSeen;
        }

        /**
         * The offending line, TIN-masked and truncated.
         *
         * <p>A rejected row's raw text contains whatever was in the TIN column, and for a
         * sole proprietor that is a Social Security Number. Rejection reports are the
         * classic place PII quietly accumulates, so masking happens here, before the value
         * is ever handed to the database.
         */
        private static String redactedLine(String[] fields) {
            String joined = String.join(",", fields);
            String masked = com.soraban.readiness.security.TinMaskingConverter.mask(joined);
            return masked.length() > 1024 ? masked.substring(0, 1024) + "..." : masked;
        }
    }

    private record RejectionRecord(String fileName, long lineNo, RejectionCode code,
                                   String detail, String redactedLine) {
    }

    /**
     * Parser settings tuned for files that are known to contain broken rows.
     *
     * <p>Two settings here exist because of one specific real-world failure, and it is the
     * most damaging one in CSV parsing:
     *
     * <p><b>An unescaped quote.</b> {@code Bob "Big Bob" Henderson} is a quote that was
     * never doubled. A strict parser treats the first quote as opening a quoted field and
     * then consumes <em>every following line</em> looking for the close &mdash; so a single
     * malformed row silently swallows hundreds of good ones. The import "succeeds" and is
     * missing data nobody notices.
     *
     * <ul>
     *   <li>{@code STOP_AT_DELIMITER} ends the value at the next delimiter instead of
     *       hunting for a closing quote. The damage is contained to the one row, which then
     *       fails the column-count check and is reported as {@code RAGGED_ROW} &mdash;
     *       exactly the outcome the brief asks for.</li>
     *   <li>{@code setMaxColumns(64)} is a backstop. Against a schema of 15 columns, a
     *       record claiming more than 64 is definitionally corrupt, and bounding it means a
     *       runaway fails fast and locally rather than after allocating for 512.</li>
     * </ul>
     *
     * <p><b>{@code setLineSeparatorDetectionEnabled(true)} is not optional here</b>, and it
     * is the setting that actually bit first. The seed generator writes {@code '\n'}
     * deliberately, never {@link System#lineSeparator()}, because a platform-dependent line
     * ending would make the "byte-identical corpus" guarantee false the moment the code ran
     * on a different operating system. univocity, meanwhile, defaults to the <em>platform's</em>
     * separator &mdash; so on Windows it read an entire LF-only file as one enormous record
     * and overflowed the column limit on the very first row. Two components each doing the
     * locally sensible thing, disagreeing at the seam.
     */
    private static CsvParserSettings parserSettings() {
        CsvParserSettings settings = new CsvParserSettings();
        settings.setHeaderExtractionEnabled(false);
        settings.setMaxCharsPerColumn(64 * 1024);
        settings.setMaxColumns(64);
        settings.setLineSeparatorDetectionEnabled(true);
        settings.setUnescapedQuoteHandling(
                com.univocity.parsers.csv.UnescapedQuoteHandling.STOP_AT_DELIMITER);
        return settings;
    }

    // =================================================================================
    // Staging
    // =================================================================================

    private void createStagingTable(String name) {
        // UNLOGGED skips WAL for the bulk write, which is the large win here and is fully
        // justified: the table is rebuildable from the file, so crash-unsafety costs
        // nothing. Unlike TEMP it also survives a FAILED import, so a second connection can
        // inspect what went wrong.
        jdbc.execute("create unlogged table stg." + name
                   + " (like stg.ledger_line_template including defaults)");
        jdbc.execute("set local maintenance_work_mem = '512MB'");
        jdbc.execute("set local work_mem = '128MB'");

        // Justified by the same property that gives us restartability: the merge is
        // idempotent, so a commit lost to an OS crash costs a re-run of a command that
        // would have been safe to re-run anyway. This trades a durability guarantee we do
        // not need for a fsync per commit we would rather not pay.
        //
        // SET LOCAL, so it reverts at commit and cannot leak to another pooled user of
        // this connection.
        jdbc.execute("set local synchronous_commit = off");
    }

    /**
     * Collapses rows sharing a conflict key, keeping the last occurrence in file order.
     *
     * <p>Required, not optional: {@code ON CONFLICT DO UPDATE} raises
     * <em>"command cannot affect row a second time"</em> when the source contains two rows
     * with the same conflict key, and QuickBooks exports genuinely double-emit rows. Without
     * this step the entire import would fail on a real-world export quirk.
     *
     * <p>Counted as a warning rather than a rejection, because the data is present and
     * correct &mdash; one copy is simply redundant.
     */
    private long dedupeStaging(String stagingTable) {
        jdbc.execute("create index on stg." + stagingTable
                   + " (client_ref, source_system, source_txn_id)");
        jdbc.execute("analyze stg." + stagingTable);

        Integer deleted = jdbc.update("""
                delete from stg.%s s
                 using stg.%s t
                 where s.ctid < t.ctid
                   and s.client_ref = t.client_ref
                   and s.source_system = t.source_system
                   and s.source_txn_id = t.source_txn_id
                """.formatted(stagingTable, stagingTable));
        return deleted == null ? 0 : deleted;
    }

    // =================================================================================
    // Vendors
    // =================================================================================

    /**
     * Creates or refreshes one vendor row per distinct identity within a client.
     *
     * <p>Identity is the blind index when a valid TIN is present, and a name-derived blind
     * index otherwise. Note this pass performs <b>no promotion</b>: merging a no-TIN vendor
     * into a TIN-bearing one requires a whole-year view of the client, which is
     * determination's job, not the importer's. Doing it here would mean the answer depended
     * on the order files happened to be imported in.
     *
     * <p>{@code distinct on} picks one row per identity; the ordering makes that choice
     * deterministic rather than whatever the planner returns, and prefers a row that
     * actually carries ciphertext.
     */
    private void upsertVendors(String stagingTable, long runId) {
        jdbc.update("""
                insert into app.vendor (
                    firm_id, client_id, natural_key, keyed_by, display_name, name_norm,
                    name_norm_version, tin_ct, tin_key_ver, tin_bidx, tin_last4, tin_status,
                    tin_raw_masked, first_seen_run_id, last_changed_run_id)
                select distinct on (c.id, coalesce(s.tin_bidx, s.name_bidx))
                       app.current_firm_id(),
                       c.id,
                       coalesce(s.tin_bidx, s.name_bidx),
                       case when s.tin_bidx is not null then 'TIN' else 'NAME' end,
                       s.vendor_name_raw,
                       s.vendor_name_norm,
                       s.name_norm_version,
                       s.tin_ct, s.tin_key_ver, s.tin_bidx, s.tin_last4, s.tin_status,
                       s.tin_raw_masked, ?, ?
                  from stg.%s s
                  join app.client c on c.client_ref = s.client_ref
                 order by c.id,
                          coalesce(s.tin_bidx, s.name_bidx),
                          (s.tin_ct is not null) desc,
                          s.payment_date desc
                on conflict (firm_id, client_id, natural_key) do update
                   set display_name        = excluded.display_name,
                       tin_ct              = coalesce(excluded.tin_ct, app.vendor.tin_ct),
                       tin_key_ver         = coalesce(excluded.tin_key_ver, app.vendor.tin_key_ver),
                       tin_last4           = coalesce(excluded.tin_last4, app.vendor.tin_last4),
                       tin_status          = excluded.tin_status,
                       tin_raw_masked      = coalesce(excluded.tin_raw_masked, app.vendor.tin_raw_masked),
                       last_changed_run_id = excluded.last_changed_run_id
                """.formatted(stagingTable), runId, runId);
    }

    // =================================================================================
    // Merge
    // =================================================================================

    private record MergeCounts(long inserted, long updated, long unchanged,
                               long tombstoned, long duplicates) {
    }

    /**
     * The idempotent merge.
     *
     * <p>{@code INSERT ... ON CONFLICT DO UPDATE} with a {@code WHERE} on the update branch,
     * chosen over {@code MERGE} because that {@code WHERE} is the whole game: a row whose
     * {@code row_hash} is unchanged produces <b>no new heap tuple, no WAL record, no index
     * churn, and no RETURNING row</b>. "Nothing changed" is therefore enforced by the
     * storage engine rather than by application logic &mdash; and, because the dirty-marking
     * trigger fires on actual writes, an unchanged row also costs nothing downstream.
     *
     * <p>{@code xmax = 0} separates inserts from updates in the same statement.
     *
     * <p>The {@code deleted_at is not null} clause resurrects a soft-deleted row that has
     * reappeared in a later export, rather than leaving it invisible forever.
     */
    private MergeCounts mergeLedger(String stagingTable, long runId) {
        List<Map<String, Object>> result = jdbc.queryForList("""
                with src as (
                  select c.id as client_id, v.id as vendor_id, s.*
                    from stg.%s s
                    join app.client c on c.client_ref = s.client_ref
                    left join app.vendor v
                      on v.client_id = c.id
                     and v.natural_key = coalesce(s.tin_bidx, s.name_bidx)
                ),
                upserted as (
                  insert into app.ledger_line (
                      firm_id, client_id, vendor_id, source_system, source_txn_id,
                      vendor_name_raw, vendor_name_norm, name_norm_version,
                      tin_bidx, tin_status, tin_last4,
                      payment_date, amount_cents, withholding_cents,
                      method_canon, is_card_or_tpso, entry_type, reverses_source_txn_id,
                      expense_class, currency, memo, row_hash,
                      first_seen_run_id, last_changed_run_id)
                  select app.current_firm_id(), client_id, vendor_id, source_system, source_txn_id,
                         vendor_name_raw, vendor_name_norm, name_norm_version,
                         tin_bidx, tin_status, tin_last4,
                         payment_date, amount_cents, withholding_cents,
                         method_canon, is_card_or_tpso, entry_type, reverses_source_txn_id,
                         expense_class, currency, memo, row_hash, ?, ?
                    from src
                  on conflict (firm_id, client_id, source_system, source_txn_id) do update
                     set vendor_id           = excluded.vendor_id,
                         vendor_name_raw     = excluded.vendor_name_raw,
                         vendor_name_norm    = excluded.vendor_name_norm,
                         name_norm_version   = excluded.name_norm_version,
                         tin_bidx            = excluded.tin_bidx,
                         tin_status          = excluded.tin_status,
                         tin_last4           = excluded.tin_last4,
                         payment_date        = excluded.payment_date,
                         amount_cents        = excluded.amount_cents,
                         withholding_cents   = excluded.withholding_cents,
                         method_canon        = excluded.method_canon,
                         is_card_or_tpso     = excluded.is_card_or_tpso,
                         entry_type          = excluded.entry_type,
                         reverses_source_txn_id = excluded.reverses_source_txn_id,
                         expense_class       = excluded.expense_class,
                         memo                = excluded.memo,
                         row_hash            = excluded.row_hash,
                         last_changed_run_id = excluded.last_changed_run_id,
                         deleted_at          = null,
                         deleted_by_run_id   = null
                   where app.ledger_line.row_hash is distinct from excluded.row_hash
                      or app.ledger_line.deleted_at is not null
                  returning (xmax = 0) as inserted
                )
                select count(*) filter (where inserted)     as ins,
                       count(*) filter (where not inserted) as upd
                  from upserted
                """.formatted(stagingTable), runId, runId);

        Map<String, Object> row = result.getFirst();
        long inserted = ((Number) row.get("ins")).longValue();
        long updated = ((Number) row.get("upd")).longValue();
        return new MergeCounts(inserted, updated, 0, 0, 0);
    }

    /**
     * Soft-deletes rows inside the manifest's declared scope that the file no longer contains.
     *
     * <p>Scope matters more than the anti-join does. "Absent from this file" is not the same
     * as "deleted": a QuickBooks export says nothing whatsoever about a client's
     * spreadsheet-sourced rows, and nothing at all about a client it does not cover. Scoping
     * to {@code (declared clients, source system, tax year)} is what makes deletion safe --
     * and it is exactly what keeps the mid-year system-switch clients intact.
     */
    private long tombstone(String stagingTable, long runId, List<Integer> coveredTaxYears,
                           List<String> declaredClientRefs, JsonNode manifest) {
        List<String> sourceSystems = new ArrayList<>();
        manifest.path("files").forEach(file -> sourceSystems.add(file.path("sourceSystem").asText()));
        if (sourceSystems.isEmpty()) {
            return 0;
        }

        // One statement covering every declared source system, rather than one per file.
        // The predicate is identical apart from source_system, so running it three times
        // meant three full scans of the client-year slice to find, in the common case,
        // nothing at all. Measured at ~15s for three files; a single pass is ~5s.
        Integer affected = jdbc.update("""
                update app.ledger_line p
                   set deleted_at = clock_timestamp(), deleted_by_run_id = ?
                  from app.client c
                 where c.id = p.client_id
                   and c.client_ref = any (?)
                   and p.source_system = any (?)
                   and p.tax_year = any (?)
                   and p.deleted_at is null
                   and not exists (
                         select 1 from stg.%s s
                          where s.source_txn_id = p.source_txn_id
                            and s.client_ref = c.client_ref
                            and s.source_system = p.source_system)
                """.formatted(stagingTable),
                runId,
                declaredClientRefs.toArray(new String[0]),
                sourceSystems.toArray(new String[0]),
                coveredTaxYears.toArray(new Integer[0]));

        return affected == null ? 0 : affected;
    }

    // =================================================================================
    // Clients, run bookkeeping
    // =================================================================================

    private void upsertClients(Path exportDir, long runId) {
        Path clientsCsv = exportDir.resolve("clients.csv");
        try (BufferedReader reader = Files.newBufferedReader(clientsCsv, StandardCharsets.UTF_8)) {
            // Shares parserSettings() rather than rolling its own. An earlier inline
            // version omitted line-separator detection and, on Windows, read the entire
            // file as a single record -- see the note on that method.
            CsvParserSettings settings = parserSettings();
            settings.setHeaderExtractionEnabled(true);
            CsvParser parser = new CsvParser(settings);
            parser.beginParsing(reader);

            List<Object[]> batch = new ArrayList<>();
            String[] fields;
            while ((fields = parser.parseNext()) != null) {
                batch.add(new Object[]{fields[0], fields[1], fields[3], fields[4], fields[5], fields[6]});
            }

            jdbc.batchUpdate("""
                    insert into app.client (firm_id, client_ref, legal_name,
                                            address_line1, city, state_code, postal_code)
                    values (app.current_firm_id(), ?, ?, ?, ?, ?, ?)
                    on conflict (firm_id, client_ref) do update
                       set legal_name = excluded.legal_name,
                           address_line1 = excluded.address_line1,
                           city = excluded.city,
                           state_code = excluded.state_code,
                           postal_code = excluded.postal_code
                    """, batch);
        } catch (IOException e) {
            throw new IllegalStateException("failed reading clients.csv: " + e.getMessage(), e);
        }
    }

    private Map<String, Long> loadClientIds() {
        Map<String, Long> map = new HashMap<>();
        // Explicit RowCallbackHandler: a bare lambda here is ambiguous between
        // RowCallbackHandler and RowMapper, and the compiler picks the wrong one.
        jdbc.query("select client_ref, id from app.client",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> map.put(rs.getString(1), rs.getLong(2)));
        return map;
    }

    private long createRun(Path exportDir, int taxYear, int revision) {
        return jdbc.queryForObject("""
                insert into app.import_run (firm_id, export_dir, tax_year, revision)
                values (app.current_firm_id(), ?, ?, ?)
                returning id
                """, Long.class, exportDir.toAbsolutePath().toString(), taxYear, revision);
    }

    private void writeRejections(long runId, List<RejectionRecord> rejections) {
        if (rejections.isEmpty()) {
            return;
        }
        List<Object[]> batch = rejections.stream()
                .map(r -> new Object[]{runId, r.fileName(), r.lineNo(), r.code().name(),
                                       r.code().columnName(), r.detail(), r.redactedLine()})
                .toList();

        jdbc.batchUpdate("""
                insert into app.import_rejection (firm_id, import_run_id, file_name, file_line_no,
                                                  reason_code, column_name, reason_detail, raw_line_redacted)
                values (app.current_firm_id(), ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private void failRun(long runId, String reason) {
        jdbc.update("""
                update app.import_run
                   set state = 'FAILED', finished_at = clock_timestamp(), failure_reason = ?
                 where id = ?
                """, reason, runId);
    }

    private void finaliseRun(long runId, long rowsRead, long rowsRejected, MergeCounts counts,
                             long unchanged, Map<String, Long> phase, Map<String, Long> byReason) {
        try {
            jdbc.update("""
                    update app.import_run
                       set state = 'COMPLETED', finished_at = clock_timestamp(),
                           rows_read = ?, rows_rejected = ?, rows_inserted = ?, rows_updated = ?,
                           rows_unchanged = ?, rows_tombstoned = ?, duplicate_keys_collapsed = ?,
                           parse_ms = ?, dedupe_ms = ?, merge_ms = ?, tombstone_ms = ?,
                           vendor_ms = ?, total_ms = ?,
                           rejection_summary = ?::jsonb
                     where id = ?
                    """,
                    rowsRead, rowsRejected, counts.inserted(), counts.updated(),
                    unchanged, counts.tombstoned(), counts.duplicates(),
                    phase.get("parse_copy_ms"), phase.get("dedupe_ms"), phase.get("merge_ms"),
                    phase.get("tombstone_ms"), phase.get("vendor_ms"), phase.get("total_ms"),
                    json.writeValueAsString(byReason), runId);
        } catch (Exception e) {
            throw new IllegalStateException("failed finalising import run " + runId, e);
        }
    }

    // =================================================================================
    // Plumbing
    // =================================================================================

    private <T> T inTransaction(String name, java.util.function.Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate template = new TransactionTemplate(transactionManager, definition);
        return template.execute(status -> body.get());
    }

    private static long time(Runnable body) {
        long t0 = System.nanoTime();
        body.run();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static final class UncheckedIOWrapper extends RuntimeException {
        UncheckedIOWrapper(IOException cause) {
            super(cause);
        }
    }
}
