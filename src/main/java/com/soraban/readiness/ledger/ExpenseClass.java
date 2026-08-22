package com.soraban.readiness.ledger;

import java.util.Locale;

/**
 * What the payment was for.
 *
 * <p>Rule 1 says a 1099-NEC is required for a vendor paid $600 or more <b>for services</b>.
 * Payments for merchandise, rent, or an expense reimbursement are not nonemployee
 * compensation and do not belong in Box 1.
 *
 * <h2>The documented assumption</h2>
 *
 * <p>A blank category is presumed {@link #SERVICES}. Hand-maintained spreadsheets very
 * often have no category column at all, and unclassified accounts-payable activity to a
 * contractor is overwhelmingly services in practice.
 *
 * <p>The alternative &mdash; presuming "not services" &mdash; would silently suppress
 * filings for every client whose bookkeeper never categorised anything, which is exactly
 * the population most likely to need the firm to get this right. Erring toward "services"
 * means at worst an extra form that a human can see and question; erring the other way
 * means a missing form nobody notices until a penalty notice arrives.
 *
 * <p>This assumption is listed in the README's decisions log, because it materially
 * changes which vendors get filed for and a reviewer should see that it was a choice
 * rather than an oversight.
 */
public enum ExpenseClass {

    /** Nonemployee compensation. The only class that counts toward the threshold. */
    SERVICES,

    /** Goods. Not reportable on a 1099-NEC. */
    MERCHANDISE,

    /** Reportable, but on Form 1099-MISC Box 1 rather than a NEC. Out of scope here. */
    RENT,

    /** Repayment of an expense the vendor incurred. Not compensation. */
    REIMBURSEMENT,

    /** Recognised as explicitly non-service but not one of the above. */
    OTHER;

    public static ExpenseClass fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return SERVICES;   // the documented default; see class javadoc
        }

        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "SERVICES", "SERVICE", "CONTRACT_LABOR", "CONTRACTOR", "PROFESSIONAL_FEES",
                 "SUBCONTRACTOR", "CONSULTING", "LABOR" -> SERVICES;
            case "MERCHANDISE", "GOODS", "INVENTORY", "SUPPLIES", "MATERIALS", "PARTS" -> MERCHANDISE;
            case "RENT", "LEASE", "RENTAL" -> RENT;
            case "REIMBURSEMENT", "EXPENSE_REIMBURSEMENT", "MILEAGE", "TRAVEL_REIMBURSEMENT" -> REIMBURSEMENT;
            default -> OTHER;
        };
    }

    /** Whether payments in this class count toward the 1099-NEC threshold. */
    public boolean isReportableAsNec() {
        return this == SERVICES;
    }
}
