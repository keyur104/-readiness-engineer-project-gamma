package com.soraban.readiness.ingest;

import com.soraban.readiness.security.TinCryptoService;
import com.soraban.readiness.security.TinProperties;
import com.soraban.readiness.seed.PaymentRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line between a rejection and an exception, which is the distinction the brief is probing.
 *
 * <blockquote>A missing TIN doesn't remove the filing obligation... never a reason to silently
 * skip the vendor.</blockquote>
 *
 * <p>Two categories, and confusing them is the failure this test exists to prevent:
 *
 * <ul>
 *   <li><b>A rejection</b> means the row <em>cannot be represented</em>. An unreadable date is not
 *       a date. A row with the wrong column count is not a row. These are skipped individually,
 *       reported with line numbers, and TIN-redacted.</li>
 *   <li><b>An exception</b> means the row is fine and the <em>situation</em> needs a person. A
 *       blank TIN, a malformed TIN, an unknown payment method &mdash; all of these import
 *       normally and become work for a human downstream.</li>
 * </ul>
 *
 * <p>Getting this backwards in either direction is expensive and quiet. Rejecting a blank TIN
 * deletes a filing obligation: the vendor simply vanishes from the books rather than appearing as
 * work to do. Accepting a ragged row lets a misaligned record write a payment date into the
 * amount column.
 *
 * <p>No Spring, no database. The normalizer is a pure function from a raw record to a result,
 * which is exactly what makes the boundary cases cheap to enumerate.
 */
class RowNormalizerTest {

    private static final long FIRM = 1L;
    private static final long CLIENT_ID = 42L;

    // =================================================================================
    // Rejections: the row cannot be represented at all
    // =================================================================================

    @Test
    @DisplayName("an unreadable date is rejected rather than guessed at")
    void unparseableDateIsRejected() {
        RowNormalizer.Result result = normalize(row(f -> f.put("payment_date", "not a date")));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection()).isEqualTo(RejectionCode.UNPARSEABLE_DATE);

        // Deliberately not "try harder". A date this system cannot read is a date it must not
        // invent, because the payment date decides the tax year -- and inventing one silently
        // moves a payment between filing years.
    }

    @Test
    @DisplayName("an unreadable amount is rejected")
    void unparseableAmountIsRejected() {
        RowNormalizer.Result result = normalize(row(f -> f.put("amount", "twelve dollars")));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection()).isEqualTo(RejectionCode.UNPARSEABLE_AMOUNT);
    }

    @Test
    @DisplayName("sub-cent precision is rejected, because money is integer cents end to end")
    void subCentAmountIsRejected() {
        RowNormalizer.Result result = normalize(row(f -> f.put("amount", "100.005")));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection()).isEqualTo(RejectionCode.SUB_CENT_AMOUNT);

        // Rounding it would be the tempting alternative and is worse: a half-cent silently
        // absorbed here is a half-cent difference between our books and the client's, and
        // nobody ever finds out which row it came from.
    }

    @Test
    @DisplayName("a non-USD row is rejected rather than converted")
    void foreignCurrencyIsRejected() {
        RowNormalizer.Result result = normalize(row(f -> f.put("currency", "EUR")));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection()).isEqualTo(RejectionCode.UNSUPPORTED_CURRENCY);

        // Converting would require a rate and a date and a policy, none of which this system
        // has. Importing it as though it were dollars would overstate the total by whatever
        // the exchange rate happens to be.
    }

    @Test
    @DisplayName("a row with the wrong column count is rejected before any field is read")
    void raggedRowIsRejected() {
        RowNormalizer.Result result = normalize(new String[]{"quickbooks", "TX-1", "C-0001"});

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection()).isEqualTo(RejectionCode.RAGGED_ROW);

        // Column count is the FIRST gate on purpose. A misaligned record parses perfectly well
        // field by field -- it just puts the date where the amount should be. Reading fields
        // out of a row whose shape is wrong is how you get a plausible number in the wrong
        // column, which is worse than any parse error.
    }

    @Test
    @DisplayName("a client reference that is missing, or not in this export, is rejected")
    void unresolvableClientIsRejected() {
        assertThat(normalize(row(f -> f.put("client_ref", ""))).rejection())
                .isEqualTo(RejectionCode.MISSING_CLIENT_REF);

        assertThat(normalize(row(f -> f.put("client_ref", "C-9999"))).rejection())
                .isEqualTo(RejectionCode.UNKNOWN_CLIENT_REF);

        // A payment that belongs to no client cannot be filed by anyone. There is no sensible
        // default -- attaching it to some other client would put one client's spend on
        // another's 1099.
    }

    @Test
    @DisplayName("a row with neither a vendor name nor a TIN is rejected: nothing to identify")
    void unidentifiableVendorIsRejected() {
        RowNormalizer.Result result = normalize(row(f -> {
            f.put("vendor_name", "");
            f.put("vendor_tin", "");
        }));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection()).isEqualTo(RejectionCode.UNIDENTIFIABLE_VENDOR);

        // The ONE case where a missing TIN contributes to a rejection -- and only because the
        // name is missing too. With neither, there is no identity to attach the payment to and
        // no exception a human could act on: there is nobody to chase for a W-9.
    }

    // =================================================================================
    // NOT rejections: the row is fine, the situation needs a person
    // =================================================================================

    @Test
    @DisplayName("a blank TIN imports normally: the obligation survives, the paperwork is chased")
    void blankTinIsNotARejection() {
        RowNormalizer.Result result = normalize(row(f -> f.put("vendor_tin", "")));

        assertThat(result.isRejected())
                .as("rejecting a blank TIN would delete a filing obligation -- the vendor would "
                  + "vanish from the books instead of appearing as work to do")
                .isFalse();

        NormalizedRow imported = result.row();
        assertThat(imported.tinStatus()).isNotEqualTo("PRESENT");
        assertThat(imported.tinBidx()).isNull();

        // The name still identifies the vendor, so determination can group and total the
        // payments -- and decide a form is required even though it cannot be transmitted yet.
        assertThat(imported.vendorNameNorm()).isNotBlank();
        assertThat(imported.nameBidx()).isNotNull();
    }

    @Test
    @DisplayName("a malformed TIN is kept and flagged, never repaired and never dropped")
    void malformedTinIsNotARejection() {
        RowNormalizer.Result result = normalize(row(f -> f.put("vendor_tin", "12345")));

        assertThat(result.isRejected()).isFalse();
        assertThat(result.row().tinStatus()).isEqualTo("MALFORMED");

        // Not padded to nine digits, which would invent an identity, and not discarded, which
        // would lose the evidence a person needs to go and ask about it.
        assertThat(result.row().tinBidx())
                .as("a malformed TIN must not be used as a grouping key")
                .isNull();
    }

    @Test
    @DisplayName("an unrecognised payment method imports as NON-card, which is the safe default")
    void unknownPaymentMethodIsNotARejection() {
        RowNormalizer.Result result = normalize(row(f -> f.put("payment_method", "carrier pigeon")));

        assertThat(result.isRejected()).isFalse();

        // The direction of the default is the whole point. Non-card COUNTS toward the $600
        // threshold, so an unknown method errs toward filing a form. Defaulting to card would
        // silently SUPPRESS a filing obligation -- and nothing downstream would ever notice,
        // because a vendor that never crosses the threshold produces no output at all.
        assertThat(result.row().cardOrTpso())
                .as("an unknown method must not suppress a filing obligation")
                .isFalse();
    }

    @Test
    @DisplayName("a payment dated outside the tax year imports normally")
    void outOfYearDateIsNotARejection() {
        RowNormalizer.Result result = normalize(row(f -> f.put("payment_date", "2024-06-15")));

        assertThat(result.isRejected()).isFalse();
        assertThat(result.row().paymentDate()).isEqualTo(LocalDate.of(2024, 6, 15));

        // Filtering it here would make it indistinguishable from a payment that was never
        // imported. Determination classifies it EXCLUDED_OUT_OF_TAX_YEAR so it still appears
        // in the explanation, which is what lets someone reconcile against the client's books.
    }

    @Test
    @DisplayName("a negative amount imports normally: reversals are data, not errors")
    void negativeAmountIsNotARejection() {
        RowNormalizer.Result result = normalize(row(f -> {
            f.put("amount", "-550.00");
            f.put("entry_type", "REVERSAL");
        }));

        assertThat(result.isRejected()).isFalse();
        assertThat(result.row().amountCents()).isEqualTo(-55_000L);
        assertThat(result.row().entryType()).isEqualTo("REVERSAL");
    }

    // =================================================================================
    // Normalization that determination depends on
    // =================================================================================

    @Test
    @DisplayName("card and third-party network methods are flagged, by synonym")
    void cardMethodsAreRecognisedThroughTheirSynonyms() {
        // These decide whether a payment counts toward the threshold at all, and bookkeepers
        // type them a dozen ways. A synonym missed here understates nothing and overstates
        // nothing visibly -- it just quietly changes whether a form is owed.
        for (String card : List.of("credit_card", "Credit Card", "CREDIT CARD", "paypal", "Visa")) {
            RowNormalizer.Result result = normalize(row(f -> f.put("payment_method", card)));
            assertThat(result.isRejected()).as(card).isFalse();
            assertThat(result.row().cardOrTpso()).as("%s must be treated as card/TPSO", card).isTrue();
        }

        for (String notCard : List.of("check", "Check", "ACH", "wire", "cash")) {
            RowNormalizer.Result result = normalize(row(f -> f.put("payment_method", notCard)));
            assertThat(result.row().cardOrTpso()).as("%s counts toward the threshold", notCard).isFalse();
        }
    }

    @Test
    @DisplayName("a TIN is normalised past its formatting, so grouping does not depend on typing")
    void tinFormattingIsNormalisedBeforeItBecomesAGroupingKey() {
        NormalizedRow hyphenated = normalize(row(f -> f.put("vendor_tin", "12-3456789"))).row();
        NormalizedRow plain = normalize(row(f -> f.put("vendor_tin", "123456789"))).row();

        // Two bookkeepers typing the same TIN differently must produce the same vendor. If the
        // blind index were computed over the raw string, this would silently split one
        // contractor into two -- each below the threshold, and neither filed.
        assertThat(hyphenated.tinBidx()).isEqualTo(plain.tinBidx());
        assertThat(hyphenated.tinLast4()).isEqualTo("6789");
    }

    @Test
    @DisplayName("no plaintext TIN survives into the ledger row")
    void theLedgerRowCarriesNoPlaintextTin() {
        NormalizedRow imported = normalize(row(f -> f.put("vendor_tin", "123456789"))).row();

        // ledger_line is the million-row table. The single most effective TIN control is not
        // cryptographic -- it is not putting the TIN there in the first place.
        assertThat(imported.tinBidx()).isNotNull().hasSize(32);
        assertThat(imported.tinCiphertext()).isNotNull();
        assertThat(String.valueOf(imported.tinRawMasked())).doesNotContain("123456789");
        assertThat(imported.toString())
                .as("not even toString() may reveal it")
                .doesNotContain("123456789");
    }

    @Test
    @DisplayName("the row hash covers determination-relevant fields, so 'only what changed' works")
    void theRowHashChangesWhenSomethingDeterminationReadsChanges() {
        byte[] baseline = normalize(row(f -> { })).row().rowHash();

        // Each of these changes an input to determination, so a re-import must see the row as
        // updated -- that is what drives the dirty set and the incremental pass.
        assertThat(normalize(row(f -> f.put("amount", "999.00"))).row().rowHash())
                .isNotEqualTo(baseline);
        assertThat(normalize(row(f -> f.put("payment_date", "2025-07-07"))).row().rowHash())
                .isNotEqualTo(baseline);
        assertThat(normalize(row(f -> f.put("payment_method", "credit_card"))).row().rowHash())
                .isNotEqualTo(baseline);
        assertThat(normalize(row(f -> f.put("vendor_tin", "987654321"))).row().rowHash())
                .isNotEqualTo(baseline);

        // And it is stable for an identical row, or every import would rewrite every row and
        // "importing the same file twice changes nothing" would be false.
        assertThat(normalize(row(f -> { })).row().rowHash()).isEqualTo(baseline);
    }

    // =================================================================================
    // Fixtures
    // =================================================================================

    /** A well-formed canonical row, with the given fields overridden. */
    private static String[] row(java.util.function.Consumer<Map<String, String>> overrides) {
        Map<String, String> values = new HashMap<>(Map.of(
                "source_system", "quickbooks",
                "source_txn_id", "TX-1001",
                "client_ref", "C-0001",
                "vendor_name", "Acme Plumbing",
                "vendor_tin", "123456789",
                "vendor_tin_type", "EIN",
                "payment_date", "2025-03-04",
                "amount", "1200.00",
                "currency", "USD",
                "payment_method", "check"));
        values.put("entry_type", "PAYMENT");
        values.put("reverses_source_txn_id", "");
        values.put("backup_withholding", "0.00");
        values.put("expense_category", "SERVICES");
        values.put("memo", "invoice 17");

        overrides.accept(values);

        return Arrays.stream(PaymentRow.HEADERS).map(h -> values.getOrDefault(h, "")).toArray(String[]::new);
    }

    private static RowNormalizer.Result normalize(String[] fields) {
        return newNormalizer().normalize(fields, 2);
    }

    /**
     * Uses the CANONICAL dialect: ISO dates, plain decimals, no dialect quirks.
     *
     * <p>Deliberately not a per-dialect matrix here. The dialect's own parsing has its own
     * tests; what this class is about is the decision made <em>after</em> parsing, which is the
     * same for every dialect and is where the reject-or-flag judgement lives.
     */
    private static RowNormalizer newNormalizer() {
        return new RowNormalizer(
                FIRM,
                SourceDialect.CANONICAL,
                "quickbooks",
                Map.of("C-0001", CLIENT_ID),
                new TinCryptoService(new TinProperties(
                        1, Map.of(1, key("dev-only-tin-key-0123456789abcde")),
                        key("dev-only-bidx-key-0123456789abcd"))),
                SourceDialect.CANONICAL.columnIndex(List.of(PaymentRow.HEADERS)));
    }

    private static String key(String raw) {
        return java.util.Base64.getEncoder().encodeToString(
                Arrays.copyOf(raw.getBytes(StandardCharsets.UTF_8), 32));
    }
}
