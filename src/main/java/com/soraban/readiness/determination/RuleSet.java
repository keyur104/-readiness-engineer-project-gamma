package com.soraban.readiness.determination;

import com.soraban.readiness.ledger.VendorNameNormalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The 1099-NEC rules in force for one determination run.
 *
 * <h2>Why the rules live in Java and travel as bind parameters</h2>
 *
 * <p>The determination pass is set-based SQL, but no rule constant is ever inlined into the
 * SQL text. Three reasons:
 *
 * <ol>
 *   <li><b>The rules are testable without a database.</b> {@link PaymentClassifier} applies
 *       this same record in pure Java, so the six required cases can be exercised in
 *       milliseconds and, more importantly, can be checked <em>against</em> the SQL
 *       (see the differential property test).</li>
 *   <li><b>The rules are hashable.</b> {@link #hash()} goes onto every
 *       {@code determination_run}, so "what did we believe on February 1, and under which
 *       rules" is answerable rather than inferred. A rule change is visible in the data,
 *       not just in the git history.</li>
 *   <li><b>The threshold changes across years.</b> The brief says so explicitly. A constant
 *       inlined into a SQL string is a constant nobody finds when it moves.</li>
 * </ol>
 *
 * @param taxYear         the filing year
 * @param thresholdCents  "$600 or more", inclusive, as integer cents
 * @param nameNormVersion the vendor-name normalizer version this run assumed
 */
public record RuleSet(int taxYear, long thresholdCents, short nameNormVersion) {

    /**
     * Tax year 2025: a 1099-NEC is required for a vendor paid $600 or more for services.
     *
     * <p>60000 cents, compared with {@code >=}. The inclusivity is the whole point of
     * expressing it in cents: exactly $600.00 requires a form, and an integer comparison
     * has no epsilon to argue about.
     */
    public static RuleSet forTaxYear2025() {
        return new RuleSet(2025, 60_000L, VendorNameNormalizer.VERSION);
    }

    public static RuleSet forTaxYear(int taxYear, long thresholdCents) {
        return new RuleSet(taxYear, thresholdCents, VendorNameNormalizer.VERSION);
    }

    /**
     * A stable fingerprint of every rule input that could change an outcome.
     *
     * <p>Includes {@code nameNormVersion} deliberately. Changing how vendor names are
     * normalized changes <em>who is whom</em> among vendors with no TIN, which can change
     * whether a form is owed &mdash; so it is a rule change in every sense that matters,
     * even though it lives in a different class.
     *
     * <p>A run whose hash differs from the current one cannot be trusted as still-valid,
     * which is what makes a rule change invalidate cached determinations rather than
     * quietly mixing two conventions inside one book.
     */
    public String hash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = "v1|taxYear=%d|thresholdCents=%d|nameNormVersion=%d"
                    .formatted(taxYear, thresholdCents, nameNormVersion);
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and always present", e);
        }
    }

    /** "$600 or more" &mdash; inclusive, and exact. */
    public boolean meetsThreshold(long reportableCents) {
        return reportableCents >= thresholdCents;
    }

    /**
     * Whether a form is owed.
     *
     * <p>Two independent grounds, and the order matters for the <em>reason</em> even though
     * it does not change the answer: backup withholding forces a filing regardless of
     * amount, so a $400 vendor with withholding is required for a different reason than a
     * $700 vendor without it, and the exception list groups them differently.
     */
    public boolean formRequired(long reportableCents, long withholdingCents) {
        return withholdingCents > 0 || meetsThreshold(reportableCents);
    }

    /** {@code THRESHOLD_MET} / {@code BACKUP_WITHHOLDING} / {@code BELOW_THRESHOLD}. */
    public String requirementReason(long reportableCents, long withholdingCents) {
        if (meetsThreshold(reportableCents)) {
            return "THRESHOLD_MET";
        }
        if (withholdingCents > 0) {
            return "BACKUP_WITHHOLDING";
        }
        return "BELOW_THRESHOLD";
    }
}
