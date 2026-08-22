package com.soraban.readiness.seed;

import java.util.List;

/**
 * The contents of {@code fixtures.json} &mdash; the out-of-band index of everything the
 * generator planted, and the expected outcome for each.
 *
 * <h2>Why this file exists rather than marking rows in the CSV</h2>
 *
 * <p>If the corpus flagged its own fixtures, the system would be handling rows it had been
 * told to look at, and the tests would prove very little. Planted cases therefore look
 * exactly like ordinary data &mdash; ordinary clients, ordinary vendors, scattered among
 * ordinary payments &mdash; and their locations live here instead.
 *
 * <p>This inverts the usual relationship between a test and its fixture. The generator
 * states, in advance and in writing, what the determination engine <em>must</em> conclude;
 * the test then holds the engine to it. Neither can quietly drift toward the other, because
 * the expectation is computed by different code from a different direction.
 *
 * @param seed         the root seed that produced this corpus
 * @param taxYear      the filing year
 * @param generatedAt  ISO-8601 UTC; informational only and never read by a test, since a
 *                     timestamp would break byte-identical comparison of the JSON itself
 * @param cases        planted determination scenarios
 * @param defects      planted malformed and semantically-flawed rows
 * @param aggregates   corpus-wide counts, so tests can assert totals without re-deriving them
 */
public record FixtureManifest(
        long seed,
        int taxYear,
        String generatedAt,
        List<PlantedCase> cases,
        List<PlantedDefect> defects,
        Aggregates aggregates
) {

    /**
     * One planted determination scenario.
     *
     * @param caseId        matches {@link FixtureCase#name()}
     * @param canonical     true for the single instance named for the focused test; the
     *                      other ~24 plantings of the same case are asserted in aggregate
     * @param firm          firm slug
     * @param clientRef     the client this vendor belongs to
     * @param vendorTin     nine digits, or {@code null} for the no-TIN cases
     * @param spellings     every name variation used for this vendor
     * @param expected      what determination must conclude
     */
    public record PlantedCase(
            String caseId,
            boolean canonical,
            String firm,
            String clientRef,
            String vendorTin,
            List<String> spellings,
            Expectation expected
    ) {
    }

    /**
     * What the determination engine must produce for a planted case.
     *
     * <p>Deliberately records the full decomposition rather than just the final answer. If
     * a test only asserted {@code reportableCents}, an implementation could arrive at the
     * right total by the wrong route &mdash; for instance netting a card refund against
     * non-card payments, which happens to balance in some cases and is badly wrong in
     * others. Asserting gross, card-excluded, reversal, and reportable separately pins the
     * arithmetic, not just its result.
     *
     * @param vendorCount        distinct vendors the planted rows must resolve to (1 proves
     *                           the three-spellings case; 2 proves the ambiguous case does
     *                           <em>not</em> merge)
     * @param paymentCount       ledger rows belonging to this vendor
     * @param grossCents         total positive, in-year payments before any exclusion
     * @param cardExcludedCents  the portion excluded as card/third-party network
     * @param reversalCents      the portion removed by reversals and refunds
     * @param reportableCents    Box 1 &mdash; and simultaneously the threshold basis
     * @param withholdingCents   Box 4
     * @param formRequired       whether a 1099-NEC is owed
     * @param requirementReason  {@code THRESHOLD_MET} / {@code BACKUP_WITHHOLDING} / {@code BELOW_THRESHOLD}
     * @param exceptions         determination exception codes this vendor must raise
     */
    public record Expectation(
            int vendorCount,
            int paymentCount,
            long grossCents,
            long cardExcludedCents,
            long reversalCents,
            long reportableCents,
            long withholdingCents,
            boolean formRequired,
            String requirementReason,
            List<String> exceptions
    ) {
    }

    /**
     * One deliberately broken row.
     *
     * <p>Recording the expected reason code here means the rejection <b>report</b> is
     * itself asserted, not merely the fact that the import survived. An importer that
     * skipped every bad row for the wrong stated reason would pass a "did it survive?"
     * test and fail this one.
     *
     * @param defectClass  matches {@link DefectClass#name()}
     * @param firm         firm slug
     * @param file         the file the row was written to
     * @param lineNumber   1-based line number within that file, header included
     * @param expectedCode the reason code the importer or determination pass must record
     * @param outcome      {@code REJECTED} / {@code IMPORTED_WITH_EXCEPTION} / {@code WARNED}
     */
    public record PlantedDefect(
            String defectClass,
            String firm,
            String file,
            long lineNumber,
            String expectedCode,
            String outcome
    ) {
    }

    /**
     * Corpus-wide counts.
     *
     * @param totalRows            ledger lines written across every firm
     * @param rowsByFirm           per-firm line counts, keyed by slug
     * @param clientCount          business clients
     * @param vendorCount          distinct vendors
     * @param rejectableRowCount   rows the importer must reject
     * @param expectedFilingCount  vendors that must end up requiring a form
     * @param caseCounts           plantings per case id, so aggregate assertions know the
     *                             denominator without counting the list
     */
    public record Aggregates(
            long totalRows,
            java.util.Map<String, Long> rowsByFirm,
            int clientCount,
            int vendorCount,
            long rejectableRowCount,
            long expectedFilingCount,
            java.util.Map<String, Integer> caseCounts
    ) {
    }
}
