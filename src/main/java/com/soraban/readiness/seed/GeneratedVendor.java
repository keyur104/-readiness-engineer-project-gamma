package com.soraban.readiness.seed;

import java.util.List;

/**
 * A vendor as the generator conceives of it, before any payments exist.
 *
 * <p>The {@code spellings} list is the interesting field. Most vendors have exactly one
 * spelling, but a realistic ledger has plenty entered inconsistently &mdash; and Part 2
 * case 1 requires at least some vendor recorded under three variations of their name with
 * a single TIN. Holding the variations here rather than perturbing the name at write time
 * means the generator knows exactly what it planted, so {@code fixtures.json} can state
 * the expected outcome precisely rather than approximately.
 *
 * @param vendorKey     stable within a client; how the generator refers to this vendor
 * @param spellings     one or more name variations; payments draw from these
 * @param tin           the nine digits, or {@code null} when the client never collected a W-9
 * @param tinRawFormat  how the TIN is punctuated in the CSV ({@code 12-3456789} vs
 *                      {@code 123-45-6789} vs bare) &mdash; real exports vary, and the
 *                      punctuation is the only signal distinguishing an EIN from an SSN
 * @param tinType       {@code EIN} / {@code SSN} / blank
 * @param withholding   whether backup withholding is taken; forces a filing regardless of amount
 * @param soleProprietor a person paid under their own name, whose TIN is an SSN &mdash; the
 *                      population the TIN-protection design actually exists for
 * @param fixtureCase   the planted scenario this vendor embodies, or {@code null} for
 *                      ordinary vendors
 */
public record GeneratedVendor(
        String vendorKey,
        List<String> spellings,
        String tin,
        String tinRawFormat,
        String tinType,
        boolean withholding,
        boolean soleProprietor,
        FixtureCase fixtureCase
) {

    /** The spelling used when only one is needed (client lists, fixture descriptions). */
    public String primaryName() {
        return spellings.getFirst();
    }

    public boolean hasTin() {
        return tin != null && !tin.isBlank();
    }

    public boolean isFixture() {
        return fixtureCase != null;
    }

    /**
     * The TIN as it appears in the CSV, with this vendor's punctuation.
     *
     * <p>Returns the empty string when there is no TIN. Note that a blank here is ordinary
     * data, not a defect: a missing TIN does not remove the filing obligation, so it must
     * flow through the importer normally and surface later as an exception.
     */
    public String tinAsWritten() {
        if (!hasTin()) {
            return "";
        }
        return switch (tinRawFormat) {
            case "EIN_DASH" -> tin.substring(0, 2) + "-" + tin.substring(2);
            case "SSN_DASH" -> tin.substring(0, 3) + "-" + tin.substring(3, 5) + "-" + tin.substring(5);
            default -> tin;
        };
    }
}
