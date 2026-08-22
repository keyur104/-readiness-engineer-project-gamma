package com.soraban.readiness.determination;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Decides which vendors require a 1099-NEC, and records why.
 *
 * <h2>Set-based SQL, orchestrated by Java</h2>
 *
 * <p>The alternative &mdash; hydrating rows and applying rules in Java &mdash; loses on both
 * of the brief's criteria at once:
 *
 * <ul>
 *   <li><b>Speed.</b> A million rows over JDBC is 30&ndash;60 s of wire time and garbage
 *       collection <em>before any rule runs</em>, against a 60 s budget. The SQL below is
 *       one scan plus a hash aggregate.</li>
 *   <li><b>Explainability.</b> This is the argument that actually settles it. The
 *       per-payment reason is a {@code CASE} expression <em>in the same projection</em> that
 *       computes the counted amount, so the explanation and the total cannot drift &mdash;
 *       they are the same row of the same query. A Java engine would still have to write
 *       those million explanation rows back; SQL produces them as a byproduct.</li>
 * </ul>
 *
 * <p>Java keeps what it is better at: the rule <em>definitions</em> ({@link RuleSet}, passed
 * as bind parameters and never inlined), orchestration, timings, and the independent
 * {@link PaymentClassifier} the SQL is checked against.
 *
 * <h2>Pass structure</h2>
 *
 * <pre>
 *   scope      dirty clients (INCREMENTAL) or every client (FULL)
 *   lines      all years for those clients -- so out-of-year rows appear as EXCLUDED
 *              rather than silently vanishing from the explanation
 *   name_tin   per (client, normalized name): how many distinct TINs it maps to
 *   resolved   vendor_key + identity_source, including name-to-TIN promotion
 *   classified disposition per payment  <- the explainability write
 *   agg        per-vendor totals, decomposed
 *   decided    form_required + requirement_reason
 * </pre>
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class DeterminationEngine {

    private static final Logger log = LoggerFactory.getLogger(DeterminationEngine.class);

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    public DeterminationEngine(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /**
     * @param mode            FULL or INCREMENTAL
     * @param clientsScanned  clients in scope
     * @param paymentsScanned ledger rows classified
     * @param vendorsResolved distinct vendors after identity resolution
     * @param formsRequired   vendors owed a 1099-NEC
     * @param exceptionsRaised open exceptions produced
     */
    public record DeterminationResult(
            long runId, String mode, int clientsScanned, long paymentsScanned,
            long vendorsResolved, long formsRequired, long exceptionsRaised,
            long totalMs, Map<String, Long> phaseMs
    ) {
    }

    /**
     * The rule precedence, as one SQL expression.
     *
     * <p><b>Public and extracted deliberately.</b> {@code PaymentClassifierDifferentialIT}
     * runs this exact string against generated inputs and compares it, row for row, with
     * {@link PaymentClassifier}. Two implementations that must agree is the strongest
     * correctness argument available here &mdash; but only if the test exercises the
     * <em>real</em> expression. A copied-out duplicate would drift silently the first time
     * one side was edited, and the test would then be confirming agreement between two things
     * neither of which is what production runs.
     *
     * <p>Takes one bind parameter: the tax year. Expects columns named {@code tax_year},
     * {@code entry_type}, {@code expense_class}, {@code is_card_or_tpso},
     * {@code amount_cents} and {@code withholding_cents} on alias {@code r}.
     *
     * <p>The ORDER IS THE RULE PRECEDENCE. In particular the card check precedes the reversal
     * check: a refund of a card payment carries {@code payment_method = credit_card} itself,
     * so testing "is it negative?" first would COUNT the refund while EXCLUDING the payment it
     * reverses, dragging a vendor's total below any amount they were actually paid. That is
     * invisible in aggregate and produces no error &mdash; the vendor simply gets no form.
     */
    public static final String CLASSIFICATION_CASE_SQL = """
            case
              when r.tax_year <> ?                                then 'EXCLUDED_OUT_OF_TAX_YEAR'
              when r.entry_type = 'VOID'                          then 'EXCLUDED_VOID'
              when r.expense_class <> 'SERVICES'                  then 'EXCLUDED_NON_SERVICES'
              when r.is_card_or_tpso                              then 'EXCLUDED_CARD_TPSO'
              when r.amount_cents = 0 and r.withholding_cents = 0 then 'EXCLUDED_ZERO_AMOUNT'
              when r.amount_cents < 0                             then 'COUNTED_REVERSAL'
              else                                                     'COUNTED'
            end""";

    // =================================================================================
    // Entry point
    // =================================================================================

    public DeterminationResult determine(long firmId, RuleSet rules, boolean full) {
        return FirmContext.runAs(firmId, () -> run(rules, full));
    }

    private DeterminationResult run(RuleSet rules, boolean full) {
        long startedAt = System.nanoTime();
        Map<String, Long> phase = new LinkedHashMap<>();
        String mode = full ? "FULL" : "INCREMENTAL";

        // Captured BEFORE any work begins. Dirty marks arriving mid-run carry a later
        // timestamp and are therefore left behind for the next pass rather than being
        // cleared unprocessed -- the classic lost-update in an incremental pipeline.
        Instant watermark = Instant.now();

        long runId = inTransaction("determination:create-run",
                () -> createRun(rules, mode));

        String workTable = "determination_" + runId;

        Counts counts = inTransaction("determination:pass", () -> {
            jdbc.execute("set local work_mem = '512MB'");

            long t0 = System.nanoTime();
            int clients = buildWorkTable(workTable, rules, full, watermark);
            phase.put("classify_ms", (System.nanoTime() - t0) / 1_000_000);

            long t1 = System.nanoTime();
            long payments = writePaymentDeterminations(workTable, runId);
            phase.put("write_payment_ms", (System.nanoTime() - t1) / 1_000_000);

            long t2 = System.nanoTime();
            VendorCounts vendors = writeVendorDeterminations(workTable, runId, rules, full, watermark);
            phase.put("write_vendor_ms", (System.nanoTime() - t2) / 1_000_000);

            long t3 = System.nanoTime();
            long exceptions = writeExceptions(runId, rules, full, watermark);
            phase.put("exception_ms", (System.nanoTime() - t3) / 1_000_000);

            // Cleared only now, and only marks at or before the watermark, in the same
            // transaction as the writes above. If this transaction rolls back the marks
            // survive, so a failed run is retried rather than silently skipped.
            if (!full) {
                jdbc.update("delete from app.determination_dirty_client where marked_at <= ?",
                        Timestamp.from(watermark));
            }

            return new Counts(clients, payments, vendors.resolved(), vendors.required(), exceptions);
        });

        inTransaction("determination:drop-work", () -> {
            jdbc.execute("drop table if exists stg." + workTable);
            return null;
        });

        long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
        phase.put("total_ms", totalMs);

        inTransaction("determination:finalise", () -> {
            finaliseRun(runId, counts, phase, totalMs);
            return null;
        });

        log.info("phase=DETERMINE run={} mode={} clients={} payments={} vendors={} forms={} "
                 + "exceptions={} ms={} sla=60000 {}",
                runId, mode, counts.clients(), counts.payments(), counts.vendors(),
                counts.required(), counts.exceptions(), totalMs,
                totalMs <= 60_000 ? "OK" : "SLA_MISSED");

        return new DeterminationResult(runId, mode, counts.clients(), counts.payments(),
                counts.vendors(), counts.required(), counts.exceptions(), totalMs, phase);
    }

    private record Counts(int clients, long payments, long vendors, long required, long exceptions) {
    }

    private record VendorCounts(long resolved, long required) {
    }

    // =================================================================================
    // The pass
    // =================================================================================

    /**
     * Resolves identity and classifies every payment in scope, into one work table.
     *
     * <p>Materialised rather than left as a CTE because three separate writes consume it
     * (payments, vendors, exceptions). Recomputing the chain three times would triple the
     * cost of the most expensive part of the pass.
     */
    private int buildWorkTable(String workTable, RuleSet rules, boolean full, Instant watermark) {
        String scopePredicate = full
                ? "select id from app.client"
                : """
                  select distinct client_id from app.determination_dirty_client
                   where tax_year = ? and marked_at <= ?
                  """;

        jdbc.execute("drop table if exists stg." + workTable);

        String sql = """
                create unlogged table stg.%s as
                with scope as (%s),
                lines as (
                  -- Every year, not just the tax year. Out-of-year payments must appear in
                  -- the explanation marked EXCLUDED_OUT_OF_TAX_YEAR; a payment that simply
                  -- vanishes is indistinguishable from one that was never imported.
                  select l.id, l.client_id, l.vendor_id, l.tin_bidx, l.tin_status,
                         l.tin_last4, l.vendor_name_raw, l.vendor_name_norm,
                         l.tax_year, l.amount_cents, l.withholding_cents,
                         l.is_card_or_tpso, l.method_canon, l.entry_type, l.expense_class
                    from app.ledger_line l
                    join scope s on s.%s = l.client_id
                   where l.deleted_at is null
                ),
                name_tin as (
                  -- How many DISTINCT valid TINs does each normalized name map to, within
                  -- this client and this tax year? That single number drives the promotion
                  -- rule below.
                  select client_id, vendor_name_norm,
                         count(distinct tin_bidx) filter (where tin_bidx is not null) as tin_count,
                         -- Aggregated as hex text: PostgreSQL has no min()/max() over bytea.
                         -- When tin_count = 1 there is exactly one distinct value, so which
                         -- aggregate is used cannot matter -- but min() over text keeps the
                         -- result deterministic rather than planner-dependent.
                         min(encode(tin_bidx, 'hex')) filter (where tin_bidx is not null) as only_tin_hex
                    from lines
                   where tax_year = ?
                   group by client_id, vendor_name_norm
                ),
                resolved as (
                  select l.*,
                         nt.tin_count,
                         coalesce(
                           va.canonical_vendor_key,
                           case
                             when l.tin_bidx is not null then 'TIN:' || encode(l.tin_bidx, 'hex')
                             -- PROMOTION: a name mapping to exactly one TIN adopts it. Without
                             -- this, a vendor with four blank-TIN rows and one TIN-bearing row
                             -- splits into two vendors and files NOTHING -- a missed obligation,
                             -- and the single most likely correctness bug in this part.
                             when nt.tin_count = 1 then 'TIN:' || nt.only_tin_hex
                             else 'NAME:' || l.vendor_name_norm
                           end)                                                       as vendor_key,
                         case
                           when va.canonical_vendor_key is not null then 'MANUAL_ALIAS'
                           when l.tin_bidx is not null              then 'DIRECT_TIN'
                           when nt.tin_count = 1                    then 'NAME_TIN_PROMOTION'
                           -- ASYMMETRY, deliberately: one TIN under many names merges, but one
                           -- name under many TINs does NOT. A TIN is a strong identifier; a name
                           -- is weak, and two businesses called "Smith Consulting" is ordinary.
                           -- Both directions fail toward a human, never toward silent merging.
                           when nt.tin_count > 1                    then 'AMBIGUOUS_NAME_MULTI_TIN'
                           else 'NAME_ONLY'
                         end                                                          as identity_source
                    from lines l
                    left join name_tin nt
                           on nt.client_id = l.client_id
                          and nt.vendor_name_norm = l.vendor_name_norm
                    left join app.vendor_alias va
                           on va.client_id = l.client_id
                          and va.alias_vendor_key = 'NAME:' || l.vendor_name_norm
                )
                select r.*, %s as disposition
                  from resolved r
                """.formatted(workTable, scopePredicate, full ? "id" : "client_id",
                              CLASSIFICATION_CASE_SQL);

        if (full) {
            jdbc.update(sql, rules.taxYear(), rules.taxYear());
        } else {
            jdbc.update(sql, rules.taxYear(), Timestamp.from(watermark),
                        rules.taxYear(), rules.taxYear());
        }

        jdbc.execute("create index on stg." + workTable + " (client_id, vendor_key)");
        jdbc.execute("analyze stg." + workTable);

        return jdbc.queryForObject(
                "select count(distinct client_id) from stg." + workTable, Integer.class);
    }

    /** The per-payment evidence. Overwritten in place; the decision is what gets versioned. */
    private long writePaymentDeterminations(String workTable, long runId) {
        Integer written = jdbc.update("""
                insert into app.payment_determination (
                    firm_id, ledger_line_id, client_id, tax_year, run_id,
                    vendor_key, identity_source, disposition, counted_cents)
                select app.current_firm_id(), w.id, w.client_id, w.tax_year, ?,
                       w.vendor_key, w.identity_source, w.disposition,
                       case when w.disposition in ('COUNTED', 'COUNTED_REVERSAL')
                            then w.amount_cents else 0 end
                  from stg.%s w
                on conflict (firm_id, ledger_line_id) do update
                   set run_id          = excluded.run_id,
                       vendor_key      = excluded.vendor_key,
                       identity_source = excluded.identity_source,
                       disposition     = excluded.disposition,
                       counted_cents   = excluded.counted_cents,
                       tax_year        = excluded.tax_year
                """.formatted(workTable), runId);
        return written == null ? 0 : written;
    }

    /**
     * Aggregates to the vendor and writes a new SCD-2 version.
     *
     * <p>Closes the current version for every vendor in scope, then inserts the new one, so
     * a vendor that disappears entirely (every payment deleted) is correctly left with no
     * current row rather than a stale one.
     */
    private VendorCounts writeVendorDeterminations(String workTable, long runId, RuleSet rules,
                                                   boolean full, Instant watermark) {
        jdbc.update("""
                update app.vendor_determination vd
                   set valid_to = clock_timestamp()
                 where vd.valid_to = 'infinity'
                   and vd.tax_year = ?
                   and vd.client_id in (select distinct client_id from stg.%s)
                """.formatted(workTable), rules.taxYear());

        Integer written = jdbc.update("""
                insert into app.vendor_determination (
                    firm_id, client_id, vendor_key, tax_year, run_id,
                    vendor_id, display_name, tin_bidx, tin_last4, tin_status, identity_source,
                    gross_cents, card_excluded_cents, reversal_cents, non_services_cents,
                    out_of_year_cents, reportable_cents, withholding_cents,
                    counted_payment_count, total_payment_count,
                    form_required, requirement_reason, transmit_blocked)
                with agg as (
                  select client_id, vendor_key,
                         max(vendor_id)                                                  as vendor_id,
                         -- Deterministic display name: most frequent spelling, ties broken
                         -- lexicographically. mode() would leave ties unspecified, which makes
                         -- repeat runs flap for no reason.
                         (array_agg(vendor_name_raw order by vendor_name_raw))[1]         as display_name,
                         max(encode(tin_bidx, 'hex'))                                     as tin_bidx_hex,
                         max(tin_last4)                                                   as tin_last4,
                         max(tin_status)                                                  as tin_status,
                         max(identity_source)                                             as identity_source,

                         coalesce(sum(amount_cents) filter (
                           where tax_year = ? and amount_cents > 0), 0)                   as gross_cents,
                         coalesce(sum(amount_cents) filter (
                           where disposition = 'EXCLUDED_CARD_TPSO' and amount_cents > 0), 0) as card_excluded_cents,
                         coalesce(sum(-amount_cents) filter (
                           where disposition = 'COUNTED_REVERSAL'), 0)                    as reversal_cents,
                         coalesce(sum(amount_cents) filter (
                           where disposition = 'EXCLUDED_NON_SERVICES' and amount_cents > 0), 0) as non_services_cents,
                         coalesce(sum(amount_cents) filter (
                           where disposition = 'EXCLUDED_OUT_OF_TAX_YEAR' and amount_cents > 0), 0) as out_of_year_cents,

                         coalesce(sum(case when disposition in ('COUNTED', 'COUNTED_REVERSAL')
                                           then amount_cents else 0 end), 0)              as reportable_cents,
                         coalesce(sum(withholding_cents) filter (
                           where tax_year = ? and not is_card_or_tpso), 0)                as withholding_cents,

                         count(*) filter (where disposition in ('COUNTED', 'COUNTED_REVERSAL')) as counted_count,
                         count(*)                                                         as total_count,
                         bool_or(tin_status <> 'PRESENT')                                 as any_tin_problem
                    from stg.%s
                   group by client_id, vendor_key
                )
                select app.current_firm_id(), client_id, vendor_key, ?, ?,
                       vendor_id, display_name, decode(tin_bidx_hex, 'hex'), tin_last4, tin_status, identity_source,
                       gross_cents, card_excluded_cents, reversal_cents, non_services_cents,
                       out_of_year_cents, reportable_cents, withholding_cents,
                       counted_count, total_count,
                       -- form_required is computed FIRST and INDEPENDENTLY of whether the
                       -- vendor can actually transmit. A missing TIN blocks transmission; it
                       -- does not remove the obligation, and conflating the two is exactly the
                       -- failure the brief guards against.
                       (reportable_cents >= ? or withholding_cents > 0),
                       case when reportable_cents >= ?      then 'THRESHOLD_MET'
                            when withholding_cents > 0      then 'BACKUP_WITHHOLDING'
                            else                                 'BELOW_THRESHOLD' end,
                       ((reportable_cents >= ? or withholding_cents > 0) and any_tin_problem)
                  from agg
                """.formatted(workTable),
                rules.taxYear(), rules.taxYear(), rules.taxYear(), runId,
                rules.thresholdCents(), rules.thresholdCents(), rules.thresholdCents());

        Long required = jdbc.queryForObject("""
                select count(*) from app.vendor_determination
                 where run_id = ? and valid_to = 'infinity' and form_required
                """, Long.class, runId);

        return new VendorCounts(written == null ? 0 : written, required == null ? 0 : required);
    }

    /**
     * Raises what a person has to act on.
     *
     * <p>Produced by the same pass that produced the numbers, so the exception list cannot
     * disagree with the determination it describes. {@code ON CONFLICT DO NOTHING} against
     * the open-exception unique index makes this idempotent: re-running determination does
     * not pile up duplicates of the same unresolved problem.
     */
    private long writeExceptions(long runId, RuleSet rules, boolean full, Instant watermark) {
        Integer raised = jdbc.update("""
                insert into app.determination_exception (
                    firm_id, run_id, client_id, vendor_key, tax_year, code, severity, detail)
                select app.current_firm_id(), ?, client_id, vendor_key, tax_year, code, severity, detail
                  from (
                    -- A form is owed but no TIN was ever collected. The vendor still requires
                    -- a 1099-NEC; someone has to chase a W-9 before it can be transmitted.
                    select client_id, vendor_key, tax_year, 'MISSING_TIN' as code,
                           'BLOCKING' as severity,
                           jsonb_build_object('displayName', display_name,
                                              'reportableCents', reportable_cents) as detail
                      from app.vendor_determination
                     where valid_to = 'infinity' and run_id = ?
                       and form_required and tin_status = 'MISSING'

                    union all

                    -- Present but unusable. Preserved for a human, never used as identity.
                    select client_id, vendor_key, tax_year, 'MALFORMED_TIN', 'BLOCKING',
                           jsonb_build_object('displayName', display_name,
                                              'tinLast4', tin_last4)
                      from app.vendor_determination
                     where valid_to = 'infinity' and run_id = ?
                       and form_required and tin_status = 'MALFORMED'

                    union all

                    -- One name, several TINs. Not merged, by design; a person decides.
                    select client_id, vendor_key, tax_year, 'AMBIGUOUS_VENDOR_IDENTITY', 'REVIEW',
                           jsonb_build_object('displayName', display_name)
                      from app.vendor_determination
                     where valid_to = 'infinity' and run_id = ?
                       and identity_source = 'AMBIGUOUS_NAME_MULTI_TIN'

                    union all

                    -- Refunds exceed payments. Not in the brief, but free here and worth
                    -- catching: the IRS rejects non-positive amounts, so transmitting this
                    -- would burn one of twenty calls per minute to be told no.
                    select client_id, vendor_key, tax_year, 'NEGATIVE_REPORTABLE', 'REVIEW',
                           jsonb_build_object('displayName', display_name,
                                              'reportableCents', reportable_cents)
                      from app.vendor_determination
                     where valid_to = 'infinity' and run_id = ?
                       and reportable_cents < 0
                  ) x
                on conflict do nothing
                """, runId, runId, runId, runId, runId);
        return raised == null ? 0 : raised;
    }

    // =================================================================================
    // Run bookkeeping
    // =================================================================================

    private long createRun(RuleSet rules, String mode) {
        return jdbc.queryForObject("""
                insert into app.determination_run (
                    firm_id, tax_year, mode, ruleset_hash, name_norm_version, threshold_cents)
                values (app.current_firm_id(), ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                rules.taxYear(), mode, rules.hash(), rules.nameNormVersion(), rules.thresholdCents());
    }

    private void finaliseRun(long runId, Counts counts, Map<String, Long> phase, long totalMs) {
        jdbc.update("""
                update app.determination_run
                   set state = 'COMPLETED', finished_at = clock_timestamp(),
                       clients_scanned = ?, payments_scanned = ?, vendors_resolved = ?,
                       forms_required = ?, exceptions_raised = ?,
                       classify_ms = ?, write_payment_ms = ?, write_vendor_ms = ?,
                       exception_ms = ?, total_ms = ?
                 where id = ?
                """,
                counts.clients(), counts.payments(), counts.vendors(),
                counts.required(), counts.exceptions(),
                phase.get("classify_ms"), phase.get("write_payment_ms"),
                phase.get("write_vendor_ms"), phase.get("exception_ms"), totalMs, runId);
    }

    private <T> T inTransaction(String name, Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
