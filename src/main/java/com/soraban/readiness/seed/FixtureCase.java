package com.soraban.readiness.seed;

/**
 * The determination scenarios deliberately planted in the generated corpus.
 *
 * <p>Six are mandated by the brief. Three more are added because they are where the
 * identity logic actually lives, and a reviewer will ask about all three.
 *
 * <h2>How planting works, and why it works that way</h2>
 *
 * <p>Cases are planted inside <b>ordinary clients</b>, scattered among ordinary payments.
 * Nothing in the CSV marks a row as a fixture &mdash; no flag column, no reserved client,
 * no suspicious ordering. A corpus where the interesting rows are visibly special would
 * prove only that the system handles rows it was told to look at.
 *
 * <p>Locations are published out-of-band in {@code fixtures.json}, which tests read to
 * assert against the database. For humans debugging in psql there is a second affordance:
 * fixture vendors draw TINs from a reserved {@code 99-} EIN prefix, so
 * {@code WHERE tin LIKE '99%'} finds them all by eye.
 *
 * <p>Each case is planted roughly {@link #PLANTINGS_PER_CASE} times across different
 * clients and <b>both firms</b>, with one canonical instance named in {@code fixtures.json}
 * for the focused test and the rest asserted in aggregate ("exactly 25 vendors match the
 * case-5 profile; all 25 are BELOW_THRESHOLD"). Testing the rule at N=1 proves the rule
 * fires; testing at N=25 across two firms proves it fires consistently and does not leak
 * across the tenancy boundary.
 */
public enum FixtureCase {

    // ---------------------------------------------------------------------------------
    // The six required by the brief
    // ---------------------------------------------------------------------------------

    /**
     * Case 1 &mdash; the same vendor under three spellings of their name, one TIN.
     * Proves identity comes from the TIN, not the name string.
     * Expect: one vendor, three payments, $825.00 reportable, form required.
     */
    THREE_SPELLINGS_ONE_TIN,

    /**
     * Case 2 &mdash; a December payment reversed: gross $800, net $250.
     * The case that catches a gross-based implementation, which would file a form for
     * $800 that is not owed. The test asserts <b>no filing is created</b>, not merely that
     * the total is right.
     */
    DECEMBER_REVERSAL,

    /**
     * Case 3 &mdash; a vendor total of exactly $600.00.
     * Components are deliberately non-round (199.99 + 200.01 + 200.00): under {@code double}
     * those sum to 600.0000000000001, so a naive {@code >= 600.0} would pass for the wrong
     * reason. Ships with a paired negative fixture at $599.99 &mdash; either assertion alone
     * is weak.
     */
    EXACTLY_SIX_HUNDRED,

    /**
     * Case 3's paired negative &mdash; a vendor totalling exactly $599.99.
     *
     * <p>Ships alongside {@link #EXACTLY_SIX_HUNDRED} because either assertion alone is
     * weak. A system that files for everything passes the $600.00 test; a system that files
     * for nothing passes the $599.99 test. Only the pair pins the boundary, and only the
     * pair catches an implementation that used {@code >} where it needed {@code >=}.
     */
    JUST_UNDER_THRESHOLD,

    /**
     * Case 4 &mdash; a vendor with no TIN (the client never collected a W-9).
     * The obligation survives: form required, blocking exception, filing created in a
     * blocked state. The assertion that matters is that the vendor <b>exists</b> with
     * {@code form_required = true}; absence is the failure mode the brief guards against.
     */
    NO_TIN,

    /**
     * Case 5 &mdash; $2,400 paid, $1,900 of it by credit card.
     * Non-card portion is $500, below threshold, so no form. Proves the 1099-K exclusion
     * applies to the threshold test.
     */
    CARD_MIX_BELOW_THRESHOLD,

    /**
     * Case 6 &mdash; $400 paid with backup withholding taken.
     * Withholding forces a form regardless of amount. Box 1 $400.00, Box 4 $96.00.
     */
    BACKUP_WITHHOLDING,

    // ---------------------------------------------------------------------------------
    // Three the brief does not list, added because this is where identity actually breaks
    // ---------------------------------------------------------------------------------

    /**
     * Case 4b &mdash; TIN backfill. Four no-TIN rows ($520) plus one row carrying a TIN
     * ($180), all under the same normalized name.
     *
     * <p>The naive reading of "vendors are identified by TIN" splits this into two vendors
     * of $520 and $180 and files <b>nothing</b> &mdash; a missed $700 obligation. The
     * promotion rule merges them because the name maps to exactly one TIN.
     */
    TIN_BACKFILL_PROMOTION,

    /**
     * Case 4c &mdash; the same normalized name under two <em>different</em> TINs, plus some
     * no-TIN rows.
     *
     * <p>The mirror of 4b, and the reason promotion is asymmetric: one TIN under many names
     * merges (a TIN is a strong identifier), but one name under many TINs must <b>not</b>
     * (a name is weak, and two "Smith Consulting" entities genuinely exist). Raises
     * {@code AMBIGUOUS_VENDOR_IDENTITY} listing both candidates rather than guessing.
     */
    AMBIGUOUS_NAME_TWO_TINS,

    /**
     * Case 5b &mdash; $2,400 total with $1,750 card and $650 non-card.
     *
     * <p>Above threshold on the non-card portion, so a form <b>is</b> required &mdash; and
     * Box 1 is $650.00, not $2,400.00. This is the fixture that pins down the reported-amount
     * decision: the card portion is excluded from the reported amount as well as from the
     * threshold, because the processor already reports it on Form 1099-K and reporting it
     * twice would trigger an under-reporting notice against the contractor.
     */
    CARD_MIX_ABOVE_THRESHOLD;

    /** How many times each case is planted across the corpus, spread over both firms. */
    public static final int PLANTINGS_PER_CASE = 25;

    /**
     * EIN prefix reserved for fixture vendors, so a human can find every planted case in
     * psql with {@code WHERE tin_raw LIKE '99-%'}. Real generated vendors never use it.
     */
    public static final String FIXTURE_EIN_PREFIX = "99";

    /** Stable identifier written into {@code fixtures.json} and asserted by tests. */
    public String caseId() {
        return name();
    }
}
