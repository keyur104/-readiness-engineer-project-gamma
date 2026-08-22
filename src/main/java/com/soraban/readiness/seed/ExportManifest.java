package com.soraban.readiness.seed;

import java.util.List;

/**
 * The contents of {@code manifest.json} &mdash; what one firm's export directory claims
 * to contain.
 *
 * <h2>Why an export is a directory with a manifest, not a single CSV</h2>
 *
 * <p>Three things need somewhere to live, and none of them belongs on two thousand repeated
 * ledger rows:
 *
 * <ol>
 *   <li><b>Per-file checksums.</b> Used to verify an artifact is intact, and to record in
 *       {@code import_file} what has been seen before.</li>
 *   <li><b>Which source system produced each file.</b> A fallback for dialect selection
 *       when header fingerprinting is inconclusive.</li>
 *   <li><b>Declared client coverage</b> &mdash; the load-bearing one. It is what makes
 *       <em>safe deletion</em> possible.</li>
 * </ol>
 *
 * <h2>Declared coverage and why tombstoning needs it</h2>
 *
 * <p>When a revised export arrives, rows the bookkeeper deleted should be soft-deleted on
 * our side. But "absent from this file" is not the same as "deleted": a QuickBooks export
 * for client C says nothing whatsoever about client C's spreadsheet-sourced rows, and
 * nothing at all about client D.
 *
 * <p>So the manifest declares a scope &mdash; {@code (source_system, covered_tax_years,
 * client_refs)} &mdash; and the importer only tombstones rows inside it. Without this
 * declaration the importer would have to choose between never deleting anything (stale data
 * accumulates forever) or deleting everything absent from the file (a partial export
 * silently wipes the client's other rows). Both are wrong; the manifest is what makes the
 * third option available.
 *
 * <h2>Why {@code coveredTaxYears} is a list, and not just {@code taxYear}</h2>
 *
 * <p>{@code taxYear} is the <em>filing</em> year. It is not the coverage scope, and
 * conflating the two leaves a hole that is easy to miss: a real accounts-payable export
 * contains payments either side of the year boundary, so an export "for 2025" legitimately
 * carries 2024 and 2026 rows too.
 *
 * <p>Scoping deletion to {@code tax_year = 2025} would mean a row the bookkeeper deleted
 * from an adjacent year is imported once and then never removable &mdash; it lingers in the
 * ledger forever, invisible to every subsequent import. Scoping to <em>all</em> years
 * instead would be worse: a later 2024-only export would wipe every 2025 row for those
 * clients.
 *
 * <p>Declaring exactly which years the file speaks for resolves both. This was found by the
 * revised-export fixture disagreeing with the importer by two rows &mdash; the fixture said
 * 16 tombstones, the importer produced 14, and the missing two were out-of-year deletions.
 *
 * <p><b>{@code firm_id} is deliberately absent.</b> Firm identity comes from the import
 * invocation's authenticated context and is stamped server-side. A file can never assert
 * which firm it belongs to &mdash; the first brick of structural isolation, and the reason
 * a corrupt or hostile export cannot land in another firm's partition.
 *
 * @param firmSlug     which firm's book this is; informational, never used to route data
 * @param taxYear      the filing year this export covers
 * @param generatedAt  ISO-8601 UTC
 * @param seed         the seed that produced it, so a corpus can be regenerated exactly
 * @param revision     0 for the original export; N for the Nth revision
 * @param files        one entry per payments CSV
 * @param clientRefs   every client this export claims to cover
 * @param coveredTaxYears every tax year this export is authoritative for
 */
public record ExportManifest(
        String firmSlug,
        int taxYear,
        String generatedAt,
        long seed,
        int revision,
        List<FileEntry> files,
        List<String> clientRefs,
        List<Integer> coveredTaxYears
) {

    /**
     * @param name         filename, relative to the export directory
     * @param sourceSystem {@code QUICKBOOKS} / {@code XERO} / {@code SPREADSHEET}
     * @param rowCount     data rows, excluding the header
     * @param sha256       hex digest of the bytes on disk
     */
    public record FileEntry(
            String name,
            String sourceSystem,
            long rowCount,
            String sha256
    ) {
    }
}
