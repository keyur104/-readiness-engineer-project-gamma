package com.soraban.readiness.web;

import com.soraban.readiness.audit.AuditService;
import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.security.FirmUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The morning-after page.
 *
 * <h2>The whole page renders inside ONE repeatable-read transaction</h2>
 *
 * <p>This is the difference between a page that is merely accurate and one that is
 * <em>coherent</em>. The tiles, the exception list and the client table are five separate
 * queries; run in five separate transactions during an active filing run, they would be
 * five snapshots taken at five different instants.
 *
 * <p>The visible symptom would be a page showing "fully filed" in a tile while listing a
 * rejection for the same client below it &mdash; each half true, the combination false. At
 * 7 a.m. that is precisely the kind of thing someone acts on.
 *
 * <p>{@code REPEATABLE READ} gives all five the same snapshot. One consistent view of one
 * moment, and the header says which moment.
 *
 * <h2>Formatting happens in SQL, not in the template</h2>
 *
 * <p>Timestamps and money arrive pre-rendered as text. That is not laziness about
 * Thymeleaf: the JDBC type of a {@code timestamptz} depends on the driver, the type of a
 * {@code bigint} sum depends on whether it overflowed into {@code numeric}, and a template
 * that guesses wrong fails at render time in front of the user rather than at build time.
 * Rendering in the query makes the page's output a function of the query, which is also
 * what lets the same string be asserted in a test without going through a servlet.
 *
 * <h2>No caching, anywhere</h2>
 *
 * <p>{@code Cache-Control: no-store}. The brief's word is "truthful", and a cached copy of a
 * filing run's status is a stale copy by definition. The 30-second meta-refresh is crude and
 * deliberately so: it is impossible to get subtly wrong, and a reader can see exactly how
 * fresh the page is from the timestamp in the header.
 */
@Controller
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class DashboardController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * How many individual exception rows the page renders before it stops.
     *
     * <p>The cap is safe because the list is ordered by severity: the rare, dangerous items
     * are always above it, and what gets cut is the tail of bulk paperwork whose complete
     * count is already stated in the grouped table above. Rendering all 1,223 missing-TIN
     * rows would add roughly a megabyte of HTML to say something the group row says in one
     * line.
     *
     * <p>When the cap bites, the page says so. A list that silently stops is the same
     * failure as a stale rollup: it reads as complete and is not.
     */
    private static final int EXCEPTION_ROW_CAP = 200;

    /**
     * Plain-English gloss for each exception code, shown next to the grouped count.
     *
     * <p>Held here rather than in {@code app.reason_code} because these describe an
     * <em>operational</em> condition rather than a determination outcome, and because the
     * sentence a person needs at 7 a.m. is "what do I do about this", which is a property of
     * the page, not of the domain model. The determination reasons on the client page do
     * come from the database, so the words behind a number are never a second copy.
     */
    private static final Map<String, String> EXPLANATIONS = Map.ofEntries(
            Map.entry("ACK_RECONCILIATION_MISMATCH",
                    "The IRS's record of a batch disagrees with ours. Highest risk on this page: "
                    + "it is the condition that can mean a duplicate or a missing filing."),
            Map.entry("RATE_BUDGET_BREACH_DETECTED",
                    "More calls were made in a rolling minute than the budget allows. "
                    + "Treat as a defect in this system, not as a workload problem."),
            Map.entry("ORPHANED_BATCH_MEMBERSHIP",
                    "A filing is recorded in a batch that no longer accounts for it."),
            Map.entry("SUBMISSION_INDETERMINATE_TOO_LONG",
                    "We never got a receipt for this submission, so whether the IRS holds it "
                    + "is unknown. Do not resubmit by hand — a status call resolves it safely."),
            Map.entry("SUBMISSION_UNACKNOWLEDGED_TOO_LONG",
                    "We hold a receipt and the IRS has not answered yet. Polling continues; "
                    + "these filings count as filed on time."),
            Map.entry("FILING_REJECTED",
                    "The IRS refused the form. Fix the underlying data, then re-file — "
                    + "re-filing starts a new attempt epoch, so it cannot duplicate."),
            Map.entry("TRANSMISSION_RETRIES_EXHAUSTED",
                    "Automatic retries stopped. The batch is still scheduled and still being "
                    + "polled — this is a request for a person, not a dead end."),
            Map.entry("VENDOR_MISSING_TIN",
                    "A form is owed but there is no usable TIN. Chase a W-9. The obligation is "
                    + "recorded and counted regardless."),
            Map.entry("PREFLIGHT_VALIDATION_FAILED",
                    "The form would be rejected on a known rule, so it was stopped before it "
                    + "could spend a call from the rate budget."),
            Map.entry("AMENDED_DATA_FOR_INFLIGHT_FILING",
                    "A revised export changed a figure on a filing that was already sealed. "
                    + "The sent version stands; the change is held as a pending amendment."),
            Map.entry("DETERMINATION_CHANGED_AFTER_FILING",
                    "Re-running the rules would change a filing that has already been "
                    + "transmitted. Needs a correction decision."),
            Map.entry("AMBIGUOUS_VENDOR_IDENTITY",
                    "One vendor name maps to more than one TIN, so we will not guess which. "
                    + "Confirm the split and the payments will group correctly."),
            Map.entry("MISSING_TIN",
                    "A form is owed but there is no usable TIN."),
            Map.entry("MALFORMED_TIN",
                    "The TIN is present but is not nine digits. Kept, never guessed at."),
            Map.entry("NEGATIVE_REPORTABLE",
                    "Reversals exceed payments for the year, so the net is below zero. "
                    + "Usually a reversal recorded against the wrong year."),
            Map.entry("IMPORT_ROWS_REJECTED",
                    "Rows in the source file could not be represented at all. They were "
                    + "skipped individually; the rest of the file imported normally."));

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final AuditService audit;

    public DashboardController(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               AuditService audit) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.audit = audit;
    }

    // =================================================================================
    // The page
    // =================================================================================

    @GetMapping("/")
    public String dashboard(@AuthenticationPrincipal FirmUser user,
                            @RequestParam(defaultValue = "${readiness.tax-year}") int taxYear,
                            Model model,
                            HttpServletResponse response) {

        noStore(response);

        // The firm comes from the principal and from nowhere else. There is deliberately no
        // ?firm= parameter: a firm id that can be influenced by the request would reduce
        // row-level security to "we remembered to validate it", which is the exact class of
        // control this project exists to replace with a structural one.
        FirmContext.runAs(user.firmId(), () -> inSnapshot(() -> {

            List<Map<String, Object>> clients = jdbc.queryForList("""
                    select client_id, client_ref, legal_name, status,
                           n_total, n_accepted, n_rejected, n_blocked,
                           n_awaiting, n_batched, n_ready, n_attention,
                           to_char(accepted_cents / 100.0, 'FM999,999,990.00') as accepted_display,
                           accepted_cents
                      from app.v_client_status
                     where tax_year = ?
                     order by case status
                                when 'NEEDS_ATTENTION' then 0
                                when 'PARTIALLY_FILED' then 1
                                when 'AWAITING_IRS'    then 2
                                when 'READY_TO_FILE'   then 3
                                when 'FULLY_FILED'     then 4
                                else 5 end,
                              n_rejected + n_blocked desc, n_attention desc, legal_name
                    """, taxYear);

            // Grouped counts drive the top of the exception panel. Showing 1,223 individual
            // "vendor missing TIN" rows first would bury the one reconciliation discrepancy
            // that actually matters -- the page has to make the rare, dangerous thing
            // visible, and a count does that better than a thousand rows do.
            List<Map<String, Object>> exceptionGroups = jdbc.queryForList("""
                    select code,
                           min(severity)                                    as severity,
                           count(*)                                         as count,
                           to_char(min(first_seen_at), 'DD Mon HH24:MI')    as oldest_display
                      from app.v_exception
                     group by code
                     order by min(severity), count(*) desc
                    """);

            List<Map<String, Object>> exceptions = jdbc.queryForList("""
                    select e.severity, e.code, e.entity_type, e.entity_id, e.dedupe_key,
                           e.client_id, e.client_name, e.detail::text as detail_text,
                           to_char(e.first_seen_at, 'DD Mon HH24:MI:SS') as first_seen_display,
                           a.acked_by, a.note
                      from app.v_exception e
                      left join app.exception_ack a on a.dedupe_key = e.dedupe_key
                     order by e.severity, e.first_seen_at
                     limit ?
                    """, EXCEPTION_ROW_CAP);

            Map<String, Object> totals = jdbc.queryForMap("""
                    select
                      count(*) filter (where state = 'ACCEPTED')                     as accepted,
                      count(*) filter (where state = 'REJECTED')                     as rejected,
                      count(*) filter (where state = 'BLOCKED')                      as blocked,
                      count(*) filter (where state = 'SUBMITTED_UNACKNOWLEDGED')     as awaiting,
                      count(*) filter (where state in ('DRAFT','READY_TO_TRANSMIT')) as ready,
                      count(*) filter (where state = 'BATCHED')                      as batched,
                      count(*)                                                       as total
                      from app.filing where tax_year = ?
                    """, taxYear);

            // A run in progress means the numbers are moving under the reader. Saying so is
            // part of being truthful: a static figure during an active run is a figure with
            // a shelf life, and the reader should know that before acting on it.
            Map<String, Object> activity = jdbc.queryForMap("""
                    select
                      count(*) filter (where state = 'SEALED')       as sealed,
                      count(*) filter (where state = 'DISPATCHED')   as dispatched,
                      count(*) filter (where state = 'SUBMITTED')    as submitted,
                      count(*) filter (where state = 'ACKNOWLEDGED') as acknowledged,
                      count(*) filter (where needs_reconcile)        as needs_reconcile
                      from app.filing_batch
                    """);

            Map<String, Object> statusCounts = jdbc.queryForMap("""
                    select
                      count(*) filter (where status = 'NEEDS_ATTENTION') as needs_attention,
                      count(*) filter (where status = 'AWAITING_IRS')    as awaiting_irs,
                      count(*) filter (where status = 'PARTIALLY_FILED') as partially_filed,
                      count(*) filter (where status = 'FULLY_FILED')     as fully_filed,
                      count(*) filter (where status = 'READY_TO_FILE')   as ready_to_file,
                      count(*) filter (where status = 'NOTHING_TO_FILE') as nothing_to_file
                      from app.v_client_status where tax_year = ?
                    """, taxYear);

            long exceptionTotal = exceptionGroups.stream()
                    .mapToLong(row -> ((Number) row.get("count")).longValue())
                    .sum();

            model.addAttribute("clients", clients);
            model.addAttribute("exceptions", exceptions);
            model.addAttribute("exceptionGroups", exceptionGroups);
            model.addAttribute("exceptionTotal", exceptionTotal);
            model.addAttribute("exceptionsTruncated", exceptionTotal > exceptions.size());
            model.addAttribute("totals", totals);
            model.addAttribute("activity", activity);
            model.addAttribute("statusCounts", statusCounts);
            return null;
        }));

        Map<?, ?> activity = (Map<?, ?>) model.getAttribute("activity");
        boolean runInProgress = count(activity, "dispatched") + count(activity, "sealed") > 0;

        addPrincipal(model, user);
        model.addAttribute("taxYear", taxYear);
        model.addAttribute("asOf", STAMP.format(Instant.now()));
        model.addAttribute("runInProgress", runInProgress);
        model.addAttribute("deadline", filingDeadline(taxYear));
        model.addAttribute("explain", EXPLANATIONS);
        return "dashboard";
    }

    /**
     * One client's full explanation: every vendor, every payment, whether it counted, and why.
     *
     * <p>This is the brief's explainability requirement rendered as a page. Note that it is
     * a plain lookup, not a recomputation &mdash; the reasons were written by the same pass
     * that produced the totals, so the explanation shown here cannot disagree with the
     * number it explains, and cannot silently change if the rules are edited later.
     */
    @GetMapping("/client/{clientId}")
    public String client(@AuthenticationPrincipal FirmUser user,
                         @PathVariable long clientId,
                         @RequestParam(defaultValue = "${readiness.tax-year}") int taxYear,
                         Model model,
                         HttpServletResponse response) {

        noStore(response);

        boolean found = FirmContext.runAs(user.firmId(), () -> inSnapshot(() -> {

            // A client id belonging to another firm produces no row here -- not a 403.
            // Under RLS the row does not exist for this session, so an id-guessing attempt
            // is indistinguishable from a typo, and the page cannot confirm that some other
            // firm's client id is real.
            List<Map<String, Object>> client = jdbc.queryForList("""
                    select client_id, client_ref, legal_name, status,
                           n_total, n_accepted, n_rejected, n_blocked,
                           n_awaiting, n_batched, n_ready, n_attention,
                           to_char(accepted_cents / 100.0, 'FM999,999,990.00') as accepted_display
                      from app.v_client_status
                     where client_id = ? and tax_year = ?
                    """, clientId, taxYear);

            if (client.isEmpty()) {
                return false;
            }

            List<Map<String, Object>> vendors = jdbc.queryForList("""
                    select vd.vendor_key, vd.display_name, vd.tin_last4, vd.tin_status,
                           vd.identity_source, vd.form_required, vd.requirement_reason,
                           vd.transmit_blocked,
                           vd.counted_payment_count, vd.total_payment_count,
                           to_char(vd.gross_cents          / 100.0, 'FM999,999,990.00') as gross_display,
                           to_char(vd.card_excluded_cents  / 100.0, 'FM999,999,990.00') as card_display,
                           to_char(vd.reversal_cents       / 100.0, 'FM999,999,990.00') as reversal_display,
                           to_char(vd.non_services_cents   / 100.0, 'FM999,999,990.00') as non_services_display,
                           to_char(vd.out_of_year_cents    / 100.0, 'FM999,999,990.00') as out_of_year_display,
                           to_char(vd.reportable_cents     / 100.0, 'FM999,999,990.00') as reportable_display,
                           to_char(vd.withholding_cents    / 100.0, 'FM999,999,990.00') as withholding_display,
                           vd.card_excluded_cents, vd.reversal_cents,
                           vd.non_services_cents, vd.out_of_year_cents, vd.withholding_cents,
                           f.state as filing_state, f.irs_record_id, f.reject_code, f.generation
                      from app.vendor_determination vd
                      left join app.filing f
                             on f.client_id = vd.client_id
                            and f.vendor_key = vd.vendor_key
                            and f.tax_year = vd.tax_year
                     where vd.client_id = ? and vd.tax_year = ? and vd.valid_to = 'infinity'
                     order by vd.form_required desc, vd.reportable_cents desc, vd.display_name
                    """, clientId, taxYear);

            // Per-payment evidence, joined to reason_code so the words on screen come from
            // the database rather than from a second copy of them kept in a template.
            //
            // DELIBERATELY NOT FILTERED BY pd.tax_year, and that was a real bug.
            // payment_determination stores each row under the LEDGER LINE's year, not the
            // year being determined -- so an out-of-year payment is recorded as 2024 or 2026
            // and a `pd.tax_year = 2025` filter dropped every one of them from this page.
            //
            // The visible symptom was an explanation that did not reconcile: the vendor's
            // subtraction showed "less payments dated outside the tax year: -$430.00" and
            // then listed nothing that accounted for the $430. A reader could only conclude
            // the payments were never imported. That is precisely the failure this page
            // exists to avoid, arrived at from the opposite direction.
            //
            // Scoping by client and vendor_key alone is correct: a full determination
            // overwrites payment_determination in place, so there is exactly one row per
            // ledger line, and every row carrying this vendor_key IS this vendor's evidence.
            List<Map<String, Object>> payments = jdbc.queryForList("""
                    select pd.vendor_key,
                           to_char(l.payment_date, 'YYYY-MM-DD')                    as paid_on,
                           l.tax_year                                               as paid_in_year,
                           l.vendor_name_raw, l.method_canon, l.entry_type, l.memo,
                           to_char(l.amount_cents / 100.0, 'FM999,999,990.00')      as amount_display,
                           pd.disposition,
                           pd.disposition like 'COUNTED%'                            as counted,
                           to_char(pd.counted_cents / 100.0, 'FM999,999,990.00')    as counted_display,
                           coalesce(rc.human_text, pd.disposition)                  as why
                      from app.payment_determination pd
                      join app.ledger_line l on l.firm_id = pd.firm_id and l.id = pd.ledger_line_id
                      left join app.reason_code rc on rc.code = pd.disposition
                     where pd.client_id = ?
                     order by pd.vendor_key, l.payment_date, l.id
                    """, clientId);

            List<Map<String, Object>> attention = jdbc.queryForList("""
                    select severity, code, entity_type, entity_id, detail::text as detail_text,
                           to_char(first_seen_at, 'DD Mon HH24:MI:SS') as first_seen_display
                      from app.v_exception
                     where client_id = ?
                     order by severity, first_seen_at
                    """, clientId);

            model.addAttribute("client", client.get(0));
            model.addAttribute("vendors", vendors);
            model.addAttribute("attention", attention);
            model.addAttribute("paymentsByVendor", payments.stream().collect(Collectors.groupingBy(
                    row -> (String) row.get("vendor_key"), LinkedHashMap::new, Collectors.toList())));
            return true;
        }));

        addPrincipal(model, user);
        model.addAttribute("taxYear", taxYear);
        model.addAttribute("asOf", STAMP.format(Instant.now()));
        model.addAttribute("explain", EXPLANATIONS);

        if (!found) {
            // Says "no such client", never "not your client". Under RLS the row does not
            // exist for this session, so the page could not tell the difference even if it
            // wanted to -- which is what makes an id-guessing attempt uninformative.
            model.addAttribute("message",
                    "No client " + clientId + " with determinations for tax year " + taxYear + ".");
            return "error";
        }
        return "client";
    }

    /**
     * Records a human's acknowledgment of a derived exception.
     *
     * <p>This writes the <em>note about</em> the condition, never the condition. The
     * exception itself stays derived, so acknowledging one does not make it go away: it
     * disappears when it is actually fixed, and until then it keeps appearing with the
     * acknowledgment attached. That asymmetry is the entire point &mdash; an
     * "acknowledged" flag that also suppressed the row would let a busy morning quietly
     * empty this page without anything having been resolved.
     */
    @PostMapping("/exception/ack")
    public String acknowledge(@AuthenticationPrincipal FirmUser user,
                              @RequestParam String dedupeKey,
                              @RequestParam(required = false) String note,
                              @RequestParam(defaultValue = "${readiness.tax-year}") int taxYear) {

        // The actor is the principal, never a form field. An "acknowledged by" that the
        // browser supplies is an audit trail anyone can sign someone else's name to.
        FirmContext.runAs(user.firmId(), () -> inWriteTransaction(() -> {
            int rows = jdbc.update("""
                    insert into app.exception_ack (dedupe_key, acked_by, note)
                    values (?, ?, ?)
                    on conflict (firm_id, dedupe_key)
                      do update set acked_by = excluded.acked_by,
                                    note     = excluded.note,
                                    acked_at = clock_timestamp()
                    """, dedupeKey, user.loginName(), note);

            // A HUMAN action, so it is audited individually -- unlike machine work, which is
            // audited per run. It buffers into this transaction and flushes at commit, so
            // the audit entry and the acknowledgment it describes are atomic in both
            // directions: neither can exist without the other.
            audit.record(AuditService.Event.human(
                    user.loginName(), user.role(), "EXCEPTION_ACKNOWLEDGED",
                    "EXCEPTION", dedupeKey,
                    note == null ? Map.of() : Map.of("note", note)));
            return rows;
        }));

        return "redirect:/?taxYear=" + taxYear;
    }

    // =================================================================================
    // Plumbing
    // =================================================================================

    /**
     * The 1099-NEC deadline: 31 January following the tax year, rolled to the next weekday.
     *
     * <p>Computed rather than written into the template as a string, because a hard-coded
     * date is right for one season and quietly wrong for every one after it &mdash; and a
     * wrong deadline on this page is worse than no deadline, since it is exactly the figure
     * someone would use to decide whether tonight is the night to stay late.
     *
     * <p>Federal holidays are deliberately not modelled. Doing so properly means a holiday
     * calendar that must be maintained, and getting it silently stale is the same failure
     * this method exists to avoid. The weekend roll is the part that is certain.
     */
    static java.time.LocalDate filingDeadline(int taxYear) {
        java.time.LocalDate due = java.time.LocalDate.of(taxYear + 1, 1, 31);
        return switch (due.getDayOfWeek()) {
            case SATURDAY -> due.plusDays(2);
            case SUNDAY   -> due.plusDays(1);
            default       -> due;
        };
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
    }

    private static long count(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** Identity for the page header, so every screen says who is looking, and at what. */
    private static void addPrincipal(Model model, FirmUser user) {
        model.addAttribute("firm", user.firmSlug());
        model.addAttribute("user", user.displayName());
        model.addAttribute("login", user.loginName());
        model.addAttribute("role", user.role());
        model.addAttribute("isAdmin", user.isAdmin());
    }

    /**
     * One read-only {@code REPEATABLE READ} transaction for the whole page.
     *
     * <p>The isolation level is the point: every query in the render sees the same snapshot,
     * so the page describes one instant rather than several.
     */
    private <T> T inSnapshot(Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("web:dashboard-snapshot");
        definition.setReadOnly(true);
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }

    private <T> T inWriteTransaction(Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("web:exception-ack");
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }

}
