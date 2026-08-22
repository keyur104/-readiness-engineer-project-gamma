package com.soraban.readiness.determination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soraban.readiness.ingest.ImportPipeline;
import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.seed.SeedConfig;
import com.soraban.readiness.seed.SeedGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The six cases the brief requires, plus three it does not, driven through the whole pipeline.
 *
 * <h2>Why this seeds and imports rather than inserting rows</h2>
 *
 * <p>A test that writes {@code vendor_determination} rows by hand and then checks them proves
 * the assertion, not the system. These cases are about what happens to a <b>CSV file</b>: the
 * dialect has to parse it, the rejection sink has to leave it alone, the vendor resolver has to
 * group it, and the classifier has to decide it. Every one of those is a place the case could
 * break, and only an end-to-end run covers them.
 *
 * <p>So: generate a corpus, import it exactly as the CLI would, run determination, and assert
 * against the {@code fixtures.json} the generator published <em>out of band</em>. Nothing in the
 * CSV marks a row as a fixture, so the pipeline cannot treat these rows specially even by
 * accident &mdash; which is the property that makes the assertion mean something.
 *
 * <p>A 40-client, 20k-row corpus still plants all ten case types 25 times each, so the whole
 * thing costs about a second of seeding and a few of importing.
 *
 * <h2>Canonical instance and aggregate, deliberately both</h2>
 *
 * <p>Each case asserts its <b>canonical planting field by field</b> &mdash; gross, each exclusion
 * bucket, reportable, withholding, whether a form is required and for which reason &mdash;
 * because that is the level at which a rule is either right or wrong.
 *
 * <p>Then it asserts the same outcome holds across <b>all 25 plantings</b>. One passing instance
 * can be a coincidence of that client's other data; twenty-five spread across two firms and
 * hundreds of unrelated vendors cannot.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeterminationCasesIT {

    private static final int TAX_YEAR = 2025;
    private static final long THRESHOLD_CENTS = 60_000L;

    @Autowired ImportPipeline importPipeline;
    @Autowired DeterminationEngine engine;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    // Static: JUnit populates instance @TempDir fields before each TEST, not before
    // @BeforeAll -- so with the corpus built once for the class, the field has to be static
    // or it is still null when the generator runs.
    @TempDir static Path corpusDir;

    private JsonNode fixtures;
    private long northstarId;
    private long harborlineId;

    @BeforeAll
    void seedImportAndDetermine() throws Exception {
        northstarId  = firmId("northstar");
        harborlineId = firmId("harborline");

        clearBooks(northstarId);
        clearBooks(harborlineId);

        // Small but complete: every case type still lands 25 times, one of them canonical.
        SeedConfig config = new SeedConfig(
                42L, SeedConfig.DEFAULT_FIRMS, 40, 20_000L, TAX_YEAR, corpusDir, 0, false);
        new SeedGenerator(config).generate();

        fixtures = new ObjectMapper().readTree(
                Files.readString(corpusDir.resolve("fixtures.json")));

        RuleSet rules = RuleSet.forTaxYear(TAX_YEAR, THRESHOLD_CENTS);
        for (long firm : List.of(northstarId, harborlineId)) {
            String slug = firm == northstarId ? "northstar" : "harborline";
            importPipeline.importExport(firm, corpusDir.resolve("firm-" + slug));
            engine.determine(firm, rules, true);
        }
    }

    // =================================================================================
    // The brief's six
    // =================================================================================

    @Test
    @DisplayName("case 1: one vendor under three spellings with one TIN aggregates to one vendor")
    void threeSpellingsOneTin() {
        Case fixture = canonical("THREE_SPELLINGS_ONE_TIN");
        Determination actual = determinationFor(fixture);

        // The whole point: three distinct name strings, one vendor row.
        assertThat(fixture.spellings()).hasSize(3);
        assertMatchesFixture(fixture, actual);

        // Identity came from the TIN, never from the name. When a valid TIN is present the
        // name does not enter the decision at all, which is what makes this case fall out of
        // the design rather than need handling.
        assertThat(actual.identitySource()).isEqualTo("DIRECT_TIN");
        assertThat(actual.formRequired()).isTrue();

        // And the raw spellings survive on the ledger, so the client page can still show what
        // was actually recorded rather than a normalised version of it.
        List<String> recorded = inFirm(fixture.firmId(), () -> jdbc.queryForList("""
                select distinct l.vendor_name_raw
                  from app.ledger_line l
                  join app.payment_determination pd
                    on pd.firm_id = l.firm_id and pd.ledger_line_id = l.id
                 where pd.client_id = ? and pd.vendor_key = ?
                """, String.class, fixture.clientId(), actual.vendorKey()));

        assertThat(recorded)
                .as("all three spellings must still be visible for explainability")
                .containsExactlyInAnyOrderElementsOf(fixture.spellings());

        assertAllPlantings("THREE_SPELLINGS_ONE_TIN");
    }

    @Test
    @DisplayName("case 2: a December reversal takes gross $800 down to a net $250 - no form")
    void decemberReversal() {
        Case fixture = canonical("DECEMBER_REVERSAL");
        Determination actual = determinationFor(fixture);

        assertMatchesFixture(fixture, actual);
        assertThat(actual.grossCents()).isEqualTo(80_000L);
        assertThat(actual.reportableCents()).isEqualTo(25_000L);

        // Below threshold once the reversal is applied, so no form -- but the vendor is still
        // HERE, with the full decomposition. A vendor that simply vanished below the threshold
        // would be indistinguishable from one that was never imported.
        assertThat(actual.formRequired()).isFalse();
        assertThat(actual.requirementReason()).isEqualTo("BELOW_THRESHOLD");

        assertAllPlantings("DECEMBER_REVERSAL");
    }

    @Test
    @DisplayName("case 3: exactly $600.00 requires a form, and $599.99 does not")
    void exactlySixHundredIsInclusive() {
        Case atThreshold = canonical("EXACTLY_SIX_HUNDRED");
        Determination actual = determinationFor(atThreshold);

        assertMatchesFixture(atThreshold, actual);
        assertThat(actual.reportableCents()).isEqualTo(60_000L);
        assertThat(actual.formRequired()).as("'$600 or more' is inclusive").isTrue();

        // The other side of the boundary, one cent away. Asserting only the inclusive side
        // would pass just as well against a `>` comparison, which is the bug being excluded.
        Case justUnder = canonical("JUST_UNDER_THRESHOLD");
        Determination under = determinationFor(justUnder);

        assertThat(under.reportableCents()).isEqualTo(59_999L);
        assertThat(under.formRequired()).isFalse();

        assertAllPlantings("EXACTLY_SIX_HUNDRED");
        assertAllPlantings("JUST_UNDER_THRESHOLD");
    }

    @Test
    @DisplayName("case 4: a vendor with no TIN still requires a form, and is blocked, not dropped")
    void missingTinDoesNotRemoveTheObligation() {
        Case fixture = canonical("NO_TIN");
        Determination actual = determinationFor(fixture);

        assertMatchesFixture(fixture, actual);

        // The assertion the brief is really about. `form_required` is computed FIRST and
        // INDEPENDENTLY of whether we can transmit; the missing TIN then attaches a blocking
        // exception. Absence is the failure mode -- a silently skipped vendor is a missed
        // filing obligation nobody will ever notice.
        assertThat(actual.formRequired()).isTrue();
        assertThat(actual.requirementReason()).isEqualTo("THRESHOLD_MET");
        assertThat(actual.transmitBlocked()).isTrue();
        assertThat(actual.tinStatus()).isNotEqualTo("PRESENT");
        assertThat(actual.identitySource()).isEqualTo("NAME_ONLY");

        assertThat(openExceptionCodes(fixture, actual.vendorKey()))
                .as("a blocking exception, so a person is sent to collect a W-9")
                .contains("MISSING_TIN");

        assertAllPlantings("NO_TIN");
    }

    @Test
    @DisplayName("case 5: $2,400 paid with $1,900 by card leaves $500 reportable - no form")
    void cardPaymentsAreExcludedFromTheThreshold() {
        Case fixture = canonical("CARD_MIX_BELOW_THRESHOLD");
        Determination actual = determinationFor(fixture);

        assertMatchesFixture(fixture, actual);
        assertThat(actual.grossCents()).isEqualTo(240_000L);
        assertThat(actual.cardExcludedCents()).isEqualTo(190_000L);
        assertThat(actual.reportableCents()).isEqualTo(50_000L);

        // The threshold basis equals the reported basis. Counting the card portion toward the
        // threshold and then omitting it from Box 1 would file a form that reports less than
        // the amount that triggered it -- and counting it in BOTH reports the same income
        // twice under one TIN, which is the error a CPA firm actually gets called about.
        assertThat(actual.formRequired()).isFalse();

        assertAllPlantings("CARD_MIX_BELOW_THRESHOLD");
    }

    @Test
    @DisplayName("case 6: $400 with backup withholding requires a form regardless of amount")
    void backupWithholdingForcesAForm() {
        Case fixture = canonical("BACKUP_WITHHOLDING");
        Determination actual = determinationFor(fixture);

        assertMatchesFixture(fixture, actual);
        assertThat(actual.reportableCents()).isEqualTo(40_000L);
        assertThat(actual.withholdingCents()).isEqualTo(9_600L);

        // Below $600 and still required. And Box 1 is the GROSS $400, not $400 less the
        // withholding -- the CSV amount is gross of it, which is a reading of the data and
        // is documented as an assumption rather than presented as obvious.
        assertThat(actual.formRequired()).isTrue();
        assertThat(actual.requirementReason()).isEqualTo("BACKUP_WITHHOLDING");
        assertThat(actual.reportableCents())
                .as("Box 1 is gross of withholding, not net of it")
                .isGreaterThan(actual.withholdingCents());

        assertAllPlantings("BACKUP_WITHHOLDING");
    }

    // =================================================================================
    // Three the brief does not list, because this is where the identity logic lives
    // =================================================================================

    @Test
    @DisplayName("4b: a name mapping to exactly one TIN promotes its no-TIN rows into that vendor")
    void tinBackfillPromotesRatherThanSplitting() {
        Case fixture = canonical("TIN_BACKFILL_PROMOTION");
        Determination actual = determinationFor(fixture);

        assertMatchesFixture(fixture, actual);

        // Without promotion this splits into two vendors, neither over $600, and the system
        // files NOTHING -- a missed obligation that is invisible because nothing errors.
        assertThat(actual.formRequired()).isTrue();
        assertThat(actual.identitySource())
                .as("the merge is recorded, so it is explainable rather than magic")
                .isEqualTo("NAME_TIN_PROMOTION");

        assertAllPlantings("TIN_BACKFILL_PROMOTION");
    }

    @Test
    @DisplayName("4c: one name under two TINs refuses to merge and raises an exception")
    void ambiguousIdentityFailsTowardAHuman() {
        Case fixture = canonical("AMBIGUOUS_NAME_TWO_TINS");

        // The deliberate asymmetry with 4b: one TIN under many names merges, because a TIN is
        // a strong identifier. One name under many TINs does NOT, because a name is weak and
        // two "Smith Consulting" entities genuinely exist. A false merge files one
        // contractor's income under another's TIN -- a disclosure incident that looks entirely
        // plausible on screen.
        List<Map<String, Object>> vendors = inFirm(fixture.firmId(), () -> jdbc.queryForList("""
                select vendor_key, display_name, identity_source, reportable_cents
                  from app.vendor_determination
                 where client_id = ? and tax_year = ? and valid_to = 'infinity'
                   and lower(display_name) like ?
                """, fixture.clientId(), TAX_YEAR,
                "%" + fixture.spellings().get(0).toLowerCase() + "%"));

        assertThat(vendors)
                .as("the name must NOT be collapsed into one vendor")
                .hasSizeGreaterThan(1);

        List<String> codes = inFirm(fixture.firmId(), () -> jdbc.queryForList("""
                select distinct code from app.determination_exception
                 where client_id = ? and tax_year = ? and resolved_at is null
                """, String.class, fixture.clientId(), TAX_YEAR));

        assertThat(codes).contains("AMBIGUOUS_VENDOR_IDENTITY");
    }

    @Test
    @DisplayName("5b: a card-mixed vendor still above threshold files, on the non-card portion only")
    void cardMixAboveThresholdFilesOnTheNonCardPortion() {
        Case fixture = canonical("CARD_MIX_ABOVE_THRESHOLD");
        Determination actual = determinationFor(fixture);

        assertMatchesFixture(fixture, actual);
        assertThat(actual.grossCents()).isEqualTo(240_000L);
        assertThat(actual.cardExcludedCents()).isEqualTo(175_000L);
        assertThat(actual.reportableCents()).isEqualTo(65_000L);

        // Files, and Box 1 carries only the non-card portion. The mirror of case 5 -- without
        // this one, "card payments reduce the total" and "card payments suppress the form"
        // would be indistinguishable.
        assertThat(actual.formRequired()).isTrue();
        assertThat(actual.reportableCents()).isLessThan(actual.grossCents());

        assertAllPlantings("CARD_MIX_ABOVE_THRESHOLD");
    }

    // =================================================================================
    // Explainability, which is half of what Part 2 asks for
    // =================================================================================

    @Test
    @DisplayName("every payment carries a reason, and the counted ones sum to the reported total")
    void theExplanationReconcilesToTheNumberItExplains() {
        Case fixture = canonical("CARD_MIX_ABOVE_THRESHOLD");
        Determination actual = determinationFor(fixture);

        List<Map<String, Object>> payments = inFirm(fixture.firmId(), () -> jdbc.queryForList("""
                select pd.disposition, pd.counted_cents, l.amount_cents, rc.human_text
                  from app.payment_determination pd
                  join app.ledger_line l on l.firm_id = pd.firm_id and l.id = pd.ledger_line_id
                  left join app.reason_code rc on rc.code = pd.disposition
                 where pd.client_id = ? and pd.vendor_key = ? and pd.tax_year = ?
                """, fixture.clientId(), actual.vendorKey(), TAX_YEAR));

        assertThat(payments).isNotEmpty();

        // Every payment has a disposition, and every disposition has human text in the
        // database. A payment with no reason is a payment that silently vanished from the
        // explanation, which is indistinguishable from one that was never imported.
        assertThat(payments).allSatisfy(row -> {
            assertThat(row.get("disposition")).isNotNull();
            assertThat(row.get("human_text"))
                    .as("disposition %s has no row in app.reason_code", row.get("disposition"))
                    .isNotNull();
        });

        long countedSum = payments.stream()
                .mapToLong(row -> ((Number) row.get("counted_cents")).longValue()).sum();

        // The reconciliation that makes the client page trustworthy: the per-payment evidence
        // adds up to the headline number, because both come from the same query.
        assertThat(countedSum)
                .as("the counted payments must sum to the reportable total")
                .isEqualTo(actual.reportableCents());
    }

    @Test
    @DisplayName("an out-of-year payment is excluded but still explained, never dropped")
    void outOfYearPaymentsRemainVisible() {
        // NOTE THE ABSENT tax_year FILTER, which is the whole point of this test.
        //
        // payment_determination stores each row under the LEDGER LINE's year, not the year
        // being determined -- so an out-of-year payment is filed as 2024 or 2026. Writing
        // `where tax_year = 2025` here returns zero and looks like the rows were dropped.
        // The client detail page had exactly that filter, and so silently omitted every
        // out-of-year payment while still showing the subtraction that referred to them.
        List<Map<String, Object>> byYear = inFirm(northstarId, () -> jdbc.queryForList("""
                select tax_year, count(*) as n
                  from app.payment_determination
                 where disposition = 'EXCLUDED_OUT_OF_TAX_YEAR'
                 group by tax_year order by tax_year
                """));

        // The generator puts ~6% of payments in adjacent years. They must appear WITH a
        // disposition rather than be filtered out upstream: a payment that silently vanishes
        // is indistinguishable from one that was never imported, and that difference matters
        // to whoever is reconciling against the client's own books.
        assertThat(byYear)
                .as("out-of-year payments must be classified, not filtered away")
                .isNotEmpty();
        assertThat(byYear).allSatisfy(row ->
                assertThat(((Number) row.get("tax_year")).intValue()).isNotEqualTo(TAX_YEAR));

        // And the exclusion has to reconcile against the vendor bucket that reports it, or
        // the page shows a subtraction with nothing behind it.
        long bucketed = inFirm(northstarId, () -> jdbc.queryForObject("""
                select count(*) from app.vendor_determination
                 where tax_year = ? and valid_to = 'infinity' and out_of_year_cents <> 0
                """, Long.class, TAX_YEAR));

        assertThat(bucketed)
                .as("vendors carrying an out-of-year subtraction must exist to explain it")
                .isPositive();
    }

    // =================================================================================
    // Fixture plumbing
    // =================================================================================

    /** One planting, as published in {@code fixtures.json}. */
    private record Case(String caseId, String firmSlug, long firmId, String clientRef,
                        long clientId, String vendorTin, List<String> spellings, JsonNode expected) {
    }

    /** One vendor's determination, as the pipeline actually produced it. */
    private record Determination(String vendorKey, String identitySource, String tinStatus,
                                 long grossCents, long cardExcludedCents, long reversalCents,
                                 long nonServicesCents, long outOfYearCents, long reportableCents,
                                 long withholdingCents, boolean formRequired,
                                 String requirementReason, boolean transmitBlocked) {
    }

    private Case canonical(String caseId) {
        for (JsonNode node : fixtures.get("cases")) {
            if (caseId.equals(node.get("caseId").asText()) && node.path("canonical").asBoolean()) {
                return toCase(node);
            }
        }
        throw new AssertionError("no canonical planting for case " + caseId
                + " -- the generator published none, which is itself the bug");
    }

    private List<Case> allPlantings(String caseId) {
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : fixtures.get("cases")) {
            if (caseId.equals(node.get("caseId").asText())) {
                cases.add(toCase(node));
            }
        }
        return cases;
    }

    private Case toCase(JsonNode node) {
        String slug = node.get("firm").asText();
        long firm = "northstar".equals(slug) ? northstarId : harborlineId;
        String clientRef = node.get("clientRef").asText();

        long clientId = inFirm(firm, () -> jdbc.queryForObject(
                "select id from app.client where client_ref = ?", Long.class, clientRef));

        List<String> spellings = new ArrayList<>();
        node.get("spellings").forEach(s -> spellings.add(s.asText()));

        return new Case(node.get("caseId").asText(), slug, firm, clientRef, clientId,
                node.get("vendorTin").isNull() ? null : node.get("vendorTin").asText(),
                spellings, node.get("expected"));
    }

    /**
     * Finds the determination for a planted case.
     *
     * <p>Matched on display name, and additionally on the TIN's last four digits when the
     * fixture has a TIN. The name alone is not enough and the failure was instructive: the
     * generator draws vendor names from a finite word list, so across 40 clients a planted
     * "Fairview Drywall" can share its name with an unrelated organic vendor, and the lookup
     * then returned two rows. That is a collision in the <em>test's</em> addressing scheme,
     * not in the system.
     *
     * <p>Deliberately still NOT looked up by recomputing the vendor key: the key derivation is
     * part of what is under test, so using it here would make the test agree with the
     * implementation by construction. {@code tin_last4} is stored plaintext and is independent
     * of the blind-index derivation, which makes it the right discriminator.
     */
    private Determination determinationFor(Case fixture) {
        String last4 = fixture.vendorTin() == null ? null
                : fixture.vendorTin().substring(fixture.vendorTin().length() - 4);

        List<Map<String, Object>> rows = inFirm(fixture.firmId(), () -> jdbc.queryForList("""
                select vendor_key, identity_source, tin_status, gross_cents, card_excluded_cents,
                       reversal_cents, non_services_cents, out_of_year_cents, reportable_cents,
                       withholding_cents, form_required, requirement_reason, transmit_blocked
                  from app.vendor_determination
                 where client_id = ? and tax_year = ? and valid_to = 'infinity'
                   and lower(display_name) like ?
                   and (?::text is null or tin_last4 = ?)
                """, fixture.clientId(), TAX_YEAR,
                "%" + fixture.spellings().get(0).toLowerCase() + "%", last4, last4));

        assertThat(rows)
                .as("case %s at client %s: expected exactly one vendor determination for '%s'",
                        fixture.caseId(), fixture.clientRef(), fixture.spellings().get(0))
                .hasSize(1);

        Map<String, Object> row = rows.get(0);
        return new Determination(
                (String) row.get("vendor_key"), (String) row.get("identity_source"),
                (String) row.get("tin_status"),
                num(row, "gross_cents"), num(row, "card_excluded_cents"),
                num(row, "reversal_cents"), num(row, "non_services_cents"),
                num(row, "out_of_year_cents"), num(row, "reportable_cents"),
                num(row, "withholding_cents"),
                (Boolean) row.get("form_required"), (String) row.get("requirement_reason"),
                (Boolean) row.get("transmit_blocked"));
    }

    /** Every published figure, compared field by field. */
    private void assertMatchesFixture(Case fixture, Determination actual) {
        JsonNode expected = fixture.expected();
        String where = "case %s, client %s".formatted(fixture.caseId(), fixture.clientRef());

        assertThat(actual.grossCents()).as("%s: gross", where)
                .isEqualTo(expected.get("grossCents").asLong());
        assertThat(actual.cardExcludedCents()).as("%s: card excluded", where)
                .isEqualTo(expected.get("cardExcludedCents").asLong());
        assertThat(Math.abs(actual.reversalCents())).as("%s: reversals", where)
                .isEqualTo(Math.abs(expected.get("reversalCents").asLong()));
        assertThat(actual.reportableCents()).as("%s: reportable (Box 1)", where)
                .isEqualTo(expected.get("reportableCents").asLong());
        assertThat(actual.withholdingCents()).as("%s: withholding (Box 4)", where)
                .isEqualTo(expected.get("withholdingCents").asLong());
        assertThat(actual.formRequired()).as("%s: form required", where)
                .isEqualTo(expected.get("formRequired").asBoolean());
        assertThat(actual.requirementReason()).as("%s: requirement reason", where)
                .isEqualTo(expected.get("requirementReason").asText());
    }

    /**
     * The same outcome across all 25 plantings, spread over both firms.
     *
     * <p>One passing instance can be a coincidence of that client's other data. Twenty-five,
     * among hundreds of unrelated vendors, cannot be.
     */
    private void assertAllPlantings(String caseId) {
        List<Case> plantings = allPlantings(caseId);
        assertThat(plantings).as("case %s should be planted many times", caseId).hasSizeGreaterThan(5);

        List<String> failures = new ArrayList<>();
        for (Case planting : plantings) {
            try {
                assertMatchesFixture(planting, determinationFor(planting));
            } catch (AssertionError | RuntimeException e) {
                failures.add(planting.firmSlug() + "/" + planting.clientRef() + ": "
                        + e.getMessage().replaceAll("\\s+", " "));
            }
        }

        assertThat(failures)
                .as("%d of %d plantings of %s failed", failures.size(), plantings.size(), caseId)
                .isEmpty();
    }

    private List<String> openExceptionCodes(Case fixture, String vendorKey) {
        return inFirm(fixture.firmId(), () -> jdbc.queryForList("""
                select code from app.determination_exception
                 where client_id = ? and vendor_key = ? and tax_year = ? and resolved_at is null
                """, String.class, fixture.clientId(), vendorKey, TAX_YEAR));
    }

    private static long num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0L : ((Number) value).longValue();
    }

    /**
     * Clears the ledger so the corpus lands in a known state.
     *
     * <p>DELETE, not TRUNCATE: the suite connects as {@code readiness_app}, which deliberately
     * does not hold TRUNCATE, because TRUNCATE is not filtered by row-level security.
     */
    private void clearBooks(long firm) {
        FirmContext.runAs(firm, () -> transaction("test:cases-reset", false, () -> {
            jdbc.execute("delete from app.filing_batch_member");
            jdbc.execute("delete from app.transmission_attempt");
            jdbc.execute("delete from app.attention_item");
            jdbc.execute("delete from app.filing_batch");
            jdbc.execute("delete from app.filing");
            jdbc.execute("delete from app.determination_exception");
            jdbc.execute("delete from app.payment_determination");
            jdbc.execute("delete from app.vendor_determination");
            jdbc.execute("delete from app.determination_run");
            jdbc.execute("delete from app.determination_dirty_client");
            jdbc.execute("delete from app.import_rejection");
            jdbc.execute("delete from app.ledger_line");
            jdbc.execute("delete from app.import_file");
            jdbc.execute("delete from app.import_run");
            jdbc.execute("delete from app.vendor");
            jdbc.execute("delete from app.client");
            return null;
        }));
    }

    private long firmId(String slug) {
        return transaction("system:cases-firm-lookup", true, () -> jdbc.queryForObject(
                "select id from app.firm where slug = ?", Long.class, slug));
    }

    private <T> T inFirm(long firm, Supplier<T> body) {
        return FirmContext.runAs(firm, () -> transaction("test:cases-read", true, body));
    }

    private <T> T transaction(String name, boolean readOnly, Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setReadOnly(readOnly);
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
