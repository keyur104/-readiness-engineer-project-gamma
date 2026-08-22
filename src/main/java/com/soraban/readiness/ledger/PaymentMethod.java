package com.soraban.readiness.ledger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How a payment was made &mdash; the single most consequential column in Part 2.
 *
 * <p>Rule 5 of the determination rules: payments made by credit card or third-party
 * payment network do not count toward the $600 threshold, because the payment processor
 * reports them separately on Form 1099-K. So this enum's {@link #cardOrTpso} flag decides,
 * for every payment in the book, whether it counts at all.
 *
 * <h2>The conservative default, and why it goes this way</h2>
 *
 * <p>An unrecognised payment method maps to {@link #UNKNOWN}, which is <b>not</b> treated
 * as card. It imports successfully, counts toward the threshold, and raises an
 * {@code UNKNOWN_PAYMENT_METHOD} determination exception for a human.
 *
 * <p>The asymmetry is deliberate and worth being able to defend. Guessing "card" for an
 * unknown method would <em>suppress</em> a filing that may well be required &mdash; a
 * missed 1099-NEC, which is a penalty notice. Guessing "not card" at worst produces a form
 * that turns out not to have been needed, which is visible, correctable, and flagged for
 * review either way. Both directions are wrong; only one of them is silent.
 */
public enum PaymentMethod {

    CHECK("Check", false),
    ACH("ACH", false),
    WIRE("Wire", false),
    CASH("Cash", false),

    /** Reported by the card processor on Form 1099-K, so excluded from the threshold. */
    CREDIT_CARD("Credit card", true),

    /**
     * Third-party settlement organisation &mdash; PayPal, Stripe, Square, Venmo for
     * business. Same 1099-K treatment as a card, and the brief groups them together.
     */
    TPSO("Third-party network", true),

    /**
     * Present in the export but not recognised. Counts toward the threshold (see the
     * class note) and raises an exception rather than being silently resolved.
     */
    UNKNOWN("Unknown", false);

    private final String displayName;
    private final boolean cardOrTpso;

    PaymentMethod(String displayName, boolean cardOrTpso) {
        this.displayName = displayName;
        this.cardOrTpso = cardOrTpso;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the processor reports this separately on Form 1099-K. */
    public boolean isCardOrTpso() {
        return cardOrTpso;
    }

    /**
     * Free-text synonyms seen across QuickBooks, Xero, and hand-maintained spreadsheets.
     *
     * <p>Real exports do not agree on vocabulary, and bookkeepers type whatever they like
     * into a spreadsheet column. This map is deliberately generous about spelling but never
     * guesses: anything absent falls to {@link #UNKNOWN} and surfaces to a person.
     */
    private static final Map<String, PaymentMethod> SYNONYMS = buildSynonyms();

    private static Map<String, PaymentMethod> buildSynonyms() {
        Map<String, PaymentMethod> m = new HashMap<>();

        for (String s : new String[]{"check", "cheque", "chk", "chck", "paper check", "bill pay check"}) {
            m.put(s, CHECK);
        }
        for (String s : new String[]{"ach", "eft", "direct deposit", "bank transfer", "e-transfer",
                                     "electronic transfer", "bill.com ach"}) {
            m.put(s, ACH);
        }
        for (String s : new String[]{"wire", "wire transfer", "fedwire", "swift"}) {
            m.put(s, WIRE);
        }
        for (String s : new String[]{"cash", "petty cash", "currency"}) {
            m.put(s, CASH);
        }
        for (String s : new String[]{"credit card", "creditcard", "cc", "card", "visa", "mastercard",
                                     "amex", "american express", "discover", "debit card",
                                     "bill.com card", "corporate card", "p-card", "purchasing card"}) {
            m.put(s, CREDIT_CARD);
        }
        for (String s : new String[]{"paypal", "stripe", "square", "venmo", "zelle business",
                                     "third party", "third-party network", "tpso", "gusto",
                                     "bill.com", "melio", "wise"}) {
            m.put(s, TPSO);
        }
        return Map.copyOf(m);
    }

    /**
     * Maps raw export text to a canonical method.
     *
     * <p>Normalises case and whitespace, and strips a trailing reference such as
     * {@code "Check #1042"} or {@code "Chk 88213"}, which bookkeepers routinely append to
     * the method column. The check-number itself is not identity &mdash; it is captured in
     * {@code memo} for the human-facing explanation.
     *
     * <p><b>Zelle</b> is a deliberate omission from the card/TPSO list: consumer Zelle
     * transfers are not 1099-K reportable because the network does not settle funds the way
     * a TPSO does. {@code "zelle business"} maps to {@link #TPSO}; a bare {@code "zelle"}
     * falls through to {@link #UNKNOWN} and gets a human's attention, which is the right
     * outcome for a genuinely ambiguous case. This is documented as an assumption.
     */
    public static PaymentMethod fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }

        // Underscores become spaces, so a snake_case export matches the same synonyms as a
        // prose one: 'credit_card' and 'Credit Card' are the same method typed by two systems.
        //
        // This was a real gap, and the documentation is what exposed it. The CSV schema in
        // PROJECT_README lists credit_card, paypal and the rest in snake_case, and none of
        // them resolved -- so a file written exactly as documented had its card payments
        // treated as NON-card. That is the safe direction (they count toward the threshold,
        // so a form gets filed rather than suppressed) but it is still wrong, and silent.
        //
        // HYPHENS ARE DELIBERATELY LEFT ALONE. 'third-party network' and 'p-card' are
        // synonyms in their own right; folding hyphens into spaces would stop them matching.
        String normalized = raw.strip().toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replaceAll("\\s+", " ");

        PaymentMethod direct = SYNONYMS.get(normalized);
        if (direct != null) {
            return direct;
        }

        // Strip a trailing reference: "check #1042", "chk 88213", "wire ref 55".
        String withoutReference = normalized
                .replaceAll("\\s*#\\s*\\w+$", "")
                .replaceAll("\\s+(no|num|ref|reference)\\.?\\s*\\w+$", "")
                .replaceAll("\\s+\\d+$", "")
                .strip();

        PaymentMethod stripped = SYNONYMS.get(withoutReference);
        return stripped != null ? stripped : UNKNOWN;
    }
}
