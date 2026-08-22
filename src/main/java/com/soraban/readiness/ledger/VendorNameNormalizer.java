package com.soraban.readiness.ledger;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Reduces a vendor name as typed to a canonical form.
 *
 * <p>Used for two quite different jobs, and the distinction matters:
 *
 * <ol>
 *   <li><b>Display grouping</b> for vendors that <em>do</em> have a TIN. Here the
 *       normalizer is cosmetic &mdash; identity already comes from the TIN, so
 *       {@code "Acme Plumbing"}, {@code "ACME PLUMBING LLC"} and
 *       {@code "Acme Plumbing, L.L.C."} collapse to one vendor regardless of what this
 *       class does. That is Part 2 case 1, and it is handled by the TIN, not by name
 *       matching.</li>
 *   <li><b>Identity</b> for vendors that have <em>no</em> TIN. Here the normalizer is
 *       load-bearing: it is the only thing deciding whether two rows are the same
 *       contractor. Get it wrong and either one vendor becomes two (a visible extra form)
 *       or two become one (one contractor's income filed under another's name).</li>
 * </ol>
 *
 * <h2>Why there is no fuzzy matching</h2>
 *
 * <p>No trigram similarity, no Levenshtein, no automatic merge. The two error directions
 * are not symmetric:
 *
 * <ul>
 *   <li>A <b>false merge</b> reports one contractor's income under another contractor's
 *       identity. That is a wrong tax form, a penalty, and a disclosure of one client's
 *       payment history to another party. It is also nearly invisible &mdash; the totals
 *       look plausible.</li>
 *   <li>A <b>false split</b> produces two vendors where there should be one. Visible,
 *       correctable, and it surfaces as an exception.</li>
 * </ul>
 *
 * <p>So the rule is: only merge on evidence strong enough to bet a tax filing on. A
 * shared TIN is that strong. A similar name is not. Trigram similarity <em>is</em>
 * computed, but only to render a <em>suggested</em> merge on the exceptions page, never
 * applied automatically; a {@code vendor_alias} table records a human's decision and the
 * resolver honours it deterministically thereafter.
 *
 * <h2>Versioning</h2>
 *
 * <p>{@link #VERSION} is stored on every ledger row. Changing the algorithm changes who is
 * whom among no-TIN vendors, so it must be an explicit, visible migration that
 * re-resolves affected clients &mdash; never a silent reshuffle on the next deploy. The
 * determination pass treats a version bump the same way it treats a rule change: it
 * invalidates cached results rather than quietly mixing two conventions in one book.
 */
public final class VendorNameNormalizer {

    /**
     * Bump when the algorithm changes. Stored per row as {@code name_norm_version} and
     * folded into the determination ruleset hash.
     */
    public static final short VERSION = 1;

    /**
     * Trailing legal-form suffixes that carry no identity.
     *
     * <p>Stripped repeatedly, because real names stack them: {@code "ACME PLUMBING CO INC"}
     * has to reduce to {@code "ACME PLUMBING"} for the no-TIN grouping to behave.
     *
     * <p>Note what is <b>not</b> here: {@code GROUP}, {@code HOLDINGS}, {@code PARTNERS},
     * {@code ASSOCIATES}, {@code SERVICES}. Those look like noise but genuinely
     * distinguish businesses &mdash; "Anderson Group" and "Anderson" may well be two
     * different payees, and merging them is the false-merge case above.
     */
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "INC", "INCORPORATED",
            "LLC", "LLP", "LP", "PLLC",
            "CO", "COMPANY",
            "CORP", "CORPORATION",
            "LTD", "LIMITED",
            "PC", "PA", "SC",
            "DBA"
    );

    private VendorNameNormalizer() {
    }

    /**
     * Normalizes a raw vendor name.
     *
     * <p>Steps, in order:
     * <ol>
     *   <li>Unicode NFKD decomposition, then strip combining marks &mdash; so
     *       {@code "José Núñez"} and {@code "Jose Nunez"} agree. Bookkeepers are
     *       inconsistent about accents, and the same payee is often entered both ways.</li>
     *   <li>Upper-case under {@link Locale#ROOT}. Not the default locale: in a Turkish
     *       locale {@code "i"} upper-cases to a dotted capital I, which would make the
     *       normalizer produce different output on different machines and quietly break
     *       both determinism and vendor identity.</li>
     *   <li>Replace every non-alphanumeric character with a space, which folds
     *       {@code "L.L.C."} into {@code "L L C"} and drops commas, ampersands, and
     *       apostrophes.</li>
     *   <li>Collapse whitespace.</li>
     *   <li>Strip a leading {@code "THE"}.</li>
     *   <li>Strip trailing legal suffixes repeatedly. Single letters left over from
     *       punctuated forms ({@code "L L C"}) are joined back up first.</li>
     * </ol>
     *
     * @return the canonical form, or the empty string if nothing survives normalization
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");

        String upper = decomposed.toUpperCase(Locale.ROOT);

        // Any non-alphanumeric becomes a space: "Acme Plumbing, L.L.C." -> "ACME PLUMBING L L C"
        String cleaned = upper.replaceAll("[^A-Z0-9]+", " ").strip();
        if (cleaned.isEmpty()) {
            return "";
        }

        // Re-join runs of single letters so a punctuated "L L C" becomes "LLC" and can be
        // recognised as a suffix. Bounded to runs of 2-4 to avoid welding together a name
        // that is legitimately initials, e.g. "J P MORGAN" must not become "JPMORGAN".
        cleaned = joinSingleLetterRuns(cleaned);

        String[] tokens = cleaned.split(" ");
        int start = 0;
        int end = tokens.length;

        if (end - start > 1 && "THE".equals(tokens[start])) {
            start++;
        }

        // Strip stacked trailing suffixes, always leaving at least one token: a vendor
        // genuinely named "Limited" must not normalize to nothing.
        while (end - start > 1 && LEGAL_SUFFIXES.contains(tokens[end - 1])) {
            end--;
        }

        return String.join(" ", java.util.Arrays.copyOfRange(tokens, start, end));
    }

    /**
     * Joins runs of 2 to 4 consecutive single letters into one token, so that
     * {@code "ACME PLUMBING L L C"} becomes {@code "ACME PLUMBING LLC"}.
     *
     * <p>Initials collapse the same way, which is the desired behaviour:
     * {@code "J P Morgan"}, {@code "J.P. Morgan"} and {@code "JP Morgan"} all reduce to
     * {@code "JP MORGAN"} &mdash; one vendor, three spellings.
     *
     * <p>The upper bound of four is what keeps it from over-reaching. It covers every real
     * legal suffix ({@code L L C}, {@code P L L C}) and every plausible set of initials,
     * while an unbounded version would weld together longer letter sequences that carry
     * genuine meaning &mdash; for instance a name written entirely in spaced capitals,
     * where fusing every token would erase the word boundaries that distinguish it from a
     * different vendor.
     */
    private static String joinSingleLetterRuns(String text) {
        String[] tokens = text.split(" ");
        StringBuilder out = new StringBuilder(text.length());

        int i = 0;
        while (i < tokens.length) {
            int runEnd = i;
            while (runEnd < tokens.length && tokens[runEnd].length() == 1
                   && Character.isLetter(tokens[runEnd].charAt(0))) {
                runEnd++;
            }

            int runLength = runEnd - i;
            if (runLength >= 2 && runLength <= 4) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                for (int k = i; k < runEnd; k++) {
                    out.append(tokens[k]);
                }
                i = runEnd;
            } else {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(tokens[i]);
                i++;
            }
        }
        return out.toString();
    }
}
