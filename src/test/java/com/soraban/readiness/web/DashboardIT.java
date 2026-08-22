package com.soraban.readiness.web;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.security.FirmUser;
import com.soraban.readiness.transmission.domain.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the morning-after page must be true about, expressed as assertions.
 *
 * <p>The brief asks for "fast and truthful". Speed is easy to check and easy to believe.
 * Truthfulness is not, so these tests go after the specific ways a status page lies:
 * showing another firm's data, showing a full TIN, presenting an in-flight filing as an
 * unfiled one, dropping a client off a completeness list, and truncating a list that still
 * reads as complete.
 *
 * <p>The fixture below builds one client for each of the six statuses, which makes the
 * priority ordering itself testable &mdash; the interesting case is the client that is both
 * mostly-accepted and holding one blocked form, because "needs attention wins" is a
 * decision rather than an accident.
 *
 * <p>Runs through {@code MockMvc}: the real filter chain, the real controller, the real
 * Thymeleaf render, the real database, no socket. What it gives up is the network, which is
 * not where any of these bugs live.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardIT {

    private static final int TAX_YEAR = 2025;

    /** Nine consecutive digits with nothing digit-ish adjacent: the shape of a bare TIN. */
    private static final Pattern BARE_NINE_DIGITS = Pattern.compile("(?<![\\d-])\\d{9}(?![\\d-])");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private long northstar;
    private long harborline;

    /** Client ids by the status each one is built to produce. */
    private long fullyFiled;
    private long partiallyFiled;
    private long needsAttention;
    private long nothingToFile;
    private long readyToFile;
    private long awaitingIrs;

    @BeforeEach
    void buildOneClientPerStatus() {
        northstar  = firmId("northstar");
        harborline = firmId("harborline");

        clearTransmissionState(northstar);
        clearTransmissionState(harborline);

        fullyFiled     = client(northstar, "D-FULL",    "Dashwood Joinery");
        partiallyFiled = client(northstar, "D-PART",    "Perrin Landscaping");
        needsAttention = client(northstar, "D-ATTN",    "Attwater Removals");
        nothingToFile  = client(northstar, "D-NONE",    "Norbury Bookshop");
        readyToFile    = client(northstar, "D-READY",   "Redlands Catering");
        awaitingIrs    = client(northstar, "D-WAIT",    "Waverley Signage");
        long other     = client(harborline, "H-ONLY",   "Harbour Point Marine");

        filing(northstar, fullyFiled,     "ACCEPTED");
        filing(northstar, fullyFiled,     "ACCEPTED");

        filing(northstar, partiallyFiled, "ACCEPTED");
        filing(northstar, partiallyFiled, "SUBMITTED_UNACKNOWLEDGED");

        // Mostly done AND holding one blocked form. Priority order says NEEDS_ATTENTION,
        // and this is the client that proves it: a page calling this "partially filed"
        // would be telling the truth about the majority and hiding the part that matters.
        filing(northstar, needsAttention, "ACCEPTED");
        filing(northstar, needsAttention, "ACCEPTED");
        UUID blocked = filing(northstar, needsAttention, "BLOCKED");
        attentionItem(northstar, needsAttention, blocked, "VENDOR_MISSING_TIN", 5);

        // The client page explains a DETERMINATION, not a filing, so one has to exist for
        // the explainability assertions to be about anything.
        determination(northstar, fullyFiled, "Dashwood Joinery");

        filing(northstar, readyToFile,    "READY_TO_TRANSMIT");
        filing(northstar, awaitingIrs,    "SUBMITTED_UNACKNOWLEDGED");
        filing(harborline, other,         "ACCEPTED");
        // nothingToFile deliberately gets no filings at all.
    }

    // =================================================================================
    // Status derivation -- priority order, first match wins
    // =================================================================================

    @Test
    @DisplayName("all six statuses derive correctly, and needs-attention outranks progress")
    void statusIsEvaluatedInPriorityOrder() throws Exception {
        Map<Long, String> statuses = renderedStatuses(northstar);

        assertThat(statuses.get(fullyFiled)).isEqualTo("FULLY_FILED");
        assertThat(statuses.get(partiallyFiled)).isEqualTo("PARTIALLY_FILED");
        assertThat(statuses.get(readyToFile)).isEqualTo("READY_TO_FILE");
        assertThat(statuses.get(awaitingIrs)).isEqualTo("AWAITING_IRS");

        // Two of three forms accepted, one blocked. The blocked one wins.
        assertThat(statuses.get(needsAttention))
                .as("a client holding a blocked form is never reported as making progress")
                .isEqualTo("NEEDS_ATTENTION");

        // A separate bucket, not "fully filed" and not "needs attention". Folding it into
        // the first overstates the work done; folding it into the second buries the real
        // exceptions under clients that were never going to need anything.
        assertThat(statuses.get(nothingToFile)).isEqualTo("NOTHING_TO_FILE");
    }

    @Test
    @DisplayName("every client appears, including the ones with nothing to file")
    void noClientCanVanishFromACompletenessPage() throws Exception {
        long clients = inFirm(northstar, () ->
                jdbc.queryForObject("select count(*) from app.client", Long.class));

        String body = fetch("/", northstar, "sam");

        // A client that disappears from a completeness page is the worst thing this page can
        // do: absence reads as "not my problem" and there is nothing on screen to question.
        // This is what the client-by-year cross join in v_client_status exists for.
        assertThat(body).contains(clients + " in this firm");
        assertThat(renderedStatuses(northstar)).hasSize((int) clients);
    }

    // =================================================================================
    // Isolation
    // =================================================================================

    @Test
    @DisplayName("a client id from another firm renders 'not found', never that firm's data")
    void anotherFirmsClientIdIsNotFound() throws Exception {
        String body = mvc.perform(get("/client/" + fullyFiled).with(principal(harborline, "jordan")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // "No client", not "forbidden". Under RLS the row does not exist for this session,
        // so the page cannot distinguish another firm's client from a typo -- which is what
        // makes an id-guessing attempt uninformative rather than merely refused.
        assertThat(body).contains("No client " + fullyFiled);
        assertThat(body).doesNotContain("Dashwood Joinery");
        assertThat(body).doesNotContain("Box 1");
    }

    @Test
    @DisplayName("the same URL serves different firms, and no request parameter selects one")
    void theSameUrlIsScopedByThePrincipalAlone() throws Exception {
        String asNorthstar  = fetch("/", northstar,  "sam");
        String asHarborline = fetch("/", harborline, "jordan");

        assertThat(asNorthstar).contains("firm <b>northstar</b>");
        assertThat(asHarborline).contains("firm <b>harborline</b>");

        assertThat(asNorthstar).contains("Dashwood Joinery");
        assertThat(asHarborline).contains("Harbour Point Marine");

        // The part that matters: neither page mentions a client of the other, and neither
        // took a firm from the request. If a ?firm= parameter is ever reintroduced, this is
        // the assertion that notices -- the isolation would still "work" until someone
        // changed the value by hand.
        assertThat(asHarborline).doesNotContain("Dashwood Joinery");
        assertThat(asNorthstar).doesNotContain("Harbour Point Marine");
        assertThat(asNorthstar).doesNotContain("firm=");
        assertThat(asHarborline).doesNotContain("firm=");
    }

    @Test
    @DisplayName("nothing is reachable without authenticating")
    void anonymousIsSentToLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/client/" + fullyFiled)).andExpect(status().is3xxRedirection());
    }

    // =================================================================================
    // No TIN reaches a page
    // =================================================================================

    @Test
    @DisplayName("no rendered page contains anything shaped like a full TIN")
    void noPageRendersAFullTin() throws Exception {
        assertNoBareTin(fetch("/", northstar, "sam"));

        String detail = fetch("/client/" + fullyFiled, northstar, "sam");
        assertNoBareTin(detail);
    }

    private void assertNoBareTin(String body) {
        // Filing ids are UUIDs and content hashes are hex, both of which contain long digit
        // runs. Strip those first so this fails on an actual leak rather than on a batch id.
        String stripped = body
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}", " ")
                .replaceAll("\\b[0-9a-fA-F]{16,}\\b", " ");

        Matcher matcher = BARE_NINE_DIGITS.matcher(stripped);
        String found = matcher.find() ? matcher.group() : null;

        assertThat(found)
                .as("a rendered page contains a bare nine-digit sequence, which is the shape "
                    + "of a TIN; only the last four digits may ever leave the database")
                .isNull();
    }

    // =================================================================================
    // Truthfulness
    // =================================================================================

    @Test
    @DisplayName("in-flight filings read as awaiting confirmation, never as unfiled")
    void unacknowledgedIsNeverRenderedAsNotFiled() throws Exception {
        String body = fetch("/", northstar, "sam");

        assertThat(body).contains("Awaiting the IRS");
        assertThat(body).contains("submitted, confirmation not yet returned");

        // The specific lie this page must not tell. A filing in SUBMITTED_UNACKNOWLEDGED at
        // 23:59 on 2 February is filed on time -- the wait is the IRS's clock, not ours.
        // Rendering it as "not filed" is how this page would talk someone into re-sending a
        // filing that is already live.
        assertThat(body).doesNotContain("not filed yet");
        assertThat(body).doesNotContain("failed to file");
    }

    @Test
    @DisplayName("the page carries the instant it describes, and forbids caching")
    void theSnapshotInstantIsOnScreenAndNothingIsCached() throws Exception {
        MvcResult result = mvc.perform(get("/").with(principal(northstar, "sam")))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getContentAsString()).contains("as of <b>");
    }

    @Test
    @DisplayName("status is derived; only the human's note is stored")
    void nothingThatCanGoStaleIsEverTheThingDisplayed() {
        Map<String, String> kinds = new java.util.HashMap<>();
        List<Map<String, Object>> relations = inFirm(northstar, () -> jdbc.queryForList("""
                select c.relname, c.relkind
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'app'
                   and c.relname in ('v_client_status', 'v_exception', 'exception_ack')
                """));
        relations.forEach(r -> kinds.put((String) r.get("relname"), String.valueOf(r.get("relkind"))));

        // 'v' is a view; 'm' would be a materialized view; 'r' an ordinary table. A
        // materialized view here would be stale by construction, which is the one property
        // this page cannot have -- and the failure it produces is a staff member reading
        // "fully filed" at 7 a.m. about a client that is not.
        assertThat(kinds.get("v_client_status")).as("client status must be a view").isEqualTo("v");
        assertThat(kinds.get("v_exception")).as("exceptions must be a view").isEqualTo("v");
        assertThat(kinds.get("exception_ack")).as("only the note about the truth is stored")
                .isEqualTo("r");
    }

    @Test
    @DisplayName("an exception disappears when the condition clears, with no job in between")
    void resolvingTheConditionRemovesTheRow() throws Exception {
        assertThat(fetch("/", northstar, "sam")).contains("VENDOR_MISSING_TIN");

        inFirmWrite(northstar, () -> jdbc.update(
                "update app.attention_item set resolved_at = clock_timestamp() where resolved_at is null"));

        // No refresh, no reconciliation pass, no cache invalidation. The row is gone because
        // the view is a function of current state -- which is the entire argument for not
        // storing this.
        //
        // Asserts the CONDITION's disappearance rather than an empty page: v_exception also
        // surfaces import rejections from the most recent completed import run, and whether
        // one exists depends on what else has run against this database. Asserting "the page
        // is now empty" would make this test a statement about other tests.
        String after = fetch("/", northstar, "sam");
        assertThat(after).doesNotContain("VENDOR_MISSING_TIN");
    }

    @Test
    @DisplayName("the page says when it stopped listing, rather than just stopping")
    void truncationIsStatedRatherThanInferred() throws Exception {
        // Enough to cross the 200-row cap. Built here rather than in the shared fixture so
        // the other eleven tests are not paying for it.
        int extra = 205;
        for (int i = 0; i < extra; i++) {
            UUID id = filing(northstar, readyToFile, "BLOCKED");
            attentionItem(northstar, readyToFile, id, "VENDOR_MISSING_TIN", 5);
        }

        long total = inFirm(northstar, () ->
                jdbc.queryForObject("select count(*) from app.v_exception", Long.class));
        assertThat(total).isGreaterThan(200);

        // Read back from the view rather than assuming it equals `extra`: the view unions in
        // import rejections and determination exceptions too, and the page's "showing 200 of
        // N" has to agree with whatever N actually is.

        String body = fetch("/", northstar, "sam");

        // A list that silently stops reads as complete and is not -- the same failure mode
        // as a stale rollup, arrived at from a different direction.
        assertThat(body).contains("This list is capped");
        assertThat(body).contains("showing 200 of " + total);

        // And the grouped section above stays COMPLETE, which is what makes the cap safe:
        // its header reports the full open count, not the number of rows rendered below.
        //
        // The first version of this assertion looked for the total inside a single table cell.
        // That only worked while every exception shared one code -- once import rejections and
        // determination exceptions joined the view, 223 was split across several rows and
        // appeared in no cell at all. The header is where the complete figure actually lives.
        assertThat(body).contains("— " + total + " open");
    }

    // =================================================================================
    // Explainability
    // =================================================================================

    @Test
    @DisplayName("the client page shows the subtraction, not just the answer")
    void theClientPageExplainsRatherThanAsserts() throws Exception {
        String body = fetch("/client/" + fullyFiled, northstar, "sam");

        assertThat(body).contains("Dashwood Joinery");
        // The question this page exists to answer is "why is Box 1 $650 when I paid them
        // $2,400?" -- so every exclusion line is present, including the ones that are zero.
        assertThat(body).contains("Gross paid to this vendor");
        assertThat(body).contains("less card / third-party-network payments");
        assertThat(body).contains("less non-services spend");
        assertThat(body).contains("less payments dated outside the tax year");
        assertThat(body).contains("Box 1 — reportable");
    }

    // =================================================================================
    // Speed
    // =================================================================================

    @Test
    @DisplayName("the page renders well inside a second")
    void thePageIsFast() throws Exception {
        // Warm first: the initial pass compiles the template, and a figure that includes
        // one-time compilation is not the figure a reader would ever experience.
        fetch("/", northstar, "sam");

        long worst = 0;
        for (int i = 0; i < 5; i++) {
            long started = System.nanoTime();
            fetch("/", northstar, "sam");
            worst = Math.max(worst, (System.nanoTime() - started) / 1_000_000);
        }

        System.out.printf("dashboard render, worst of five: %d ms%n", worst);
        assertThat(worst).isLessThan(1_000L);
    }

    // =================================================================================
    // Fixtures
    // =================================================================================

    private String fetch(String path, long firmId, String username) throws Exception {
        return mvc.perform(get(path).with(principal(firmId, username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Extracts each client's rendered status from the page itself.
     *
     * <p>Read out of the HTML rather than queried back out of the view, so the assertion
     * covers the whole path &mdash; view, controller, template &mdash; rather than
     * re-checking the SQL against itself.
     */
    private Map<Long, String> renderedStatuses(long firmId) throws Exception {
        String body = fetch("/", firmId, "sam");
        Matcher rows = Pattern.compile(
                "<span class=\"pill ([A-Z_]+)\">.*?/client/(\\d+)\\?", Pattern.DOTALL).matcher(body);

        Map<Long, String> statuses = new java.util.LinkedHashMap<>();
        while (rows.find()) {
            statuses.put(Long.parseLong(rows.group(2)), rows.group(1));
        }
        return statuses;
    }

    private static RequestPostProcessor principal(long firmId, String username) {
        return user(new FirmUser(0L, firmId, firmId == 1 ? "northstar" : "harborline",
                username, username, "PREPARER", "{noop}unused", true));
    }

    /**
     * DELETE, not TRUNCATE &mdash; TRUNCATE is deliberately revoked from the runtime role
     * because it is not filtered by row-level security, and the test suite connects as that
     * same role on purpose. A suite running as a superuser would bypass RLS entirely and
     * would pass identically against a completely unprotected database.
     */
    private void clearTransmissionState(long firmId) {
        inFirmWrite(firmId, () -> {
            jdbc.execute("delete from app.exception_ack");
            jdbc.execute("delete from app.filing_batch_member");
            jdbc.execute("delete from app.transmission_attempt");
            jdbc.execute("delete from app.attention_item");
            jdbc.execute("delete from app.filing_batch");
            jdbc.execute("delete from app.filing");
            jdbc.execute("delete from app.determination_exception");
            jdbc.execute("delete from app.payment_determination");
            jdbc.execute("delete from app.vendor_determination");
            jdbc.execute("delete from app.determination_run");
            return null;
        });
    }

    private long client(long firmId, String ref, String name) {
        return inFirmWrite(firmId, () -> {
            jdbc.update("""
                    insert into app.client (firm_id, client_ref, legal_name)
                    values (app.current_firm_id(), ?, ?)
                    on conflict (firm_id, client_ref) do update set legal_name = excluded.legal_name
                    """, ref, name);
            return jdbc.queryForObject(
                    "select id from app.client where client_ref = ?", Long.class, ref);
        });
    }

    /**
     * One filing in a given state, with no TIN attached.
     *
     * <p>{@code tin_status = 'MISSING'} is not a shortcut &mdash; it is what makes the
     * no-TIN-on-any-page assertion meaningful. If the fixture stored a ciphertext, the page
     * would have nothing to leak and the test would pass by having nothing to find.
     */
    private UUID filing(long firmId, long clientId, String state) {
        return inFirmWrite(firmId, () -> {
            String vendorKey = "NAME:dash-" + clientId + "-" + UUID.randomUUID();
            UUID id = IdempotencyKey.filingId(firmId, clientId, TAX_YEAR, vendorKey);
            byte[] hash = IdempotencyKey.contentHash(
                    id, 1, TAX_YEAR, "PAYER-EIN", "", "", "Vendor " + clientId, 65_000L, 0L);

            jdbc.update("""
                    insert into app.filing (
                        id, firm_id, client_id, tax_year, vendor_key, state, content_hash,
                        amount_cents, withholding_cents, recipient_name, tin_status,
                        irs_record_id, reject_code)
                    values (?, app.current_firm_id(), ?, ?, ?, ?, ?, ?, 0, ?, 'MISSING', ?, ?)
                    """,
                    id, clientId, TAX_YEAR, vendorKey, state, hash, 65_000L,
                    "Vendor " + clientId,
                    "ACCEPTED".equals(state) ? "IRS-" + id : null,
                    "REJECTED".equals(state) ? "R0001" : null);
            return id;
        });
    }

    /**
     * One vendor determination, with the full decomposition populated.
     *
     * <p>Every exclusion bucket gets a non-zero value on purpose: the client page is
     * supposed to show the subtraction rather than the answer, and a fixture where all the
     * subtrahends are zero would let a template that silently dropped them still pass.
     */
    private void determination(long firmId, long clientId, String vendorName) {
        inFirmWrite(firmId, () -> {
            Long runId = jdbc.queryForObject("""
                    insert into app.determination_run
                        (firm_id, tax_year, mode, ruleset_hash, name_norm_version,
                         threshold_cents, state)
                    values (app.current_firm_id(), ?, 'FULL', 'test-ruleset', 1, 60000, 'COMPLETED')
                    returning id
                    """, Long.class, TAX_YEAR);

            jdbc.update("""
                    insert into app.vendor_determination
                        (firm_id, client_id, vendor_key, tax_year, run_id, display_name,
                         tin_last4, tin_status, identity_source,
                         gross_cents, card_excluded_cents, reversal_cents,
                         non_services_cents, out_of_year_cents, reportable_cents,
                         withholding_cents, counted_payment_count, total_payment_count,
                         form_required, requirement_reason, transmit_blocked)
                    values (app.current_firm_id(), ?, ?, ?, ?, ?,
                            '6789', 'PRESENT', 'DIRECT_TIN',
                            240000, 90000, 15000, 40000, 30000, 65000,
                            0, 4, 9,
                            true, 'THRESHOLD_MET', false)
                    """, clientId, "TIN:dash-" + clientId, TAX_YEAR, runId, vendorName);
            return null;
        });
    }

    private void attentionItem(long firmId, long clientId, UUID filingId, String type, int severity) {
        inFirmWrite(firmId, () -> jdbc.update("""
                insert into app.attention_item
                    (firm_id, client_id, entity_type, entity_id, type, severity, detail)
                values (app.current_firm_id(), ?, 'FILING', ?, ?, ?, '{}'::jsonb)
                """, clientId, filingId.toString(), type, severity));
    }

    private long firmId(String slug) {
        return inSystem(() -> jdbc.queryForObject(
                "select id from app.firm where slug = ?", Long.class, slug));
    }

    private <T> T inFirm(long firmId, Supplier<T> body) {
        return FirmContext.runAs(firmId, () -> transaction("test:dashboard-read", true, body));
    }

    private <T> T inFirmWrite(long firmId, Supplier<T> body) {
        return FirmContext.runAs(firmId, () -> transaction("test:dashboard-fixture", false, body));
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
