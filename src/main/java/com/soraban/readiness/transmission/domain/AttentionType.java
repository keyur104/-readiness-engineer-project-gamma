package com.soraban.readiness.transmission.domain;

/**
 * Why a person is needed.
 *
 * <p>Orthogonal to {@link FilingState} and {@link BatchState}: raising one of these never
 * changes a filing's state and never changes polling cadence. A batch flagged
 * {@link #SUBMISSION_UNACKNOWLEDGED_TOO_LONG} keeps polling on exactly the schedule it was
 * already on &mdash; the flag says a human should look, not that the system has given up.
 *
 * <p>Each has a severity used to order the morning-after page. The ordering principle:
 * <b>risk of a duplicate or missed filing first</b>, then IRS rejections, then paperwork.
 * At 7 a.m. the only question that matters is what needs a person <em>most</em>.
 */
public enum AttentionType {

    /**
     * Reconciliation found filings live at the IRS that we had not recorded as sent.
     *
     * <p>Severity 0 &mdash; above everything else, because it is the only entry that can
     * mean money and penalties rather than paperwork, and it is what the entire Part 3
     * design exists to surface. If this ever appears, something in the write-ahead ordering
     * is broken.
     */
    RECONCILIATION_DISCREPANCY(0),

    /**
     * A batch has been {@code DISPATCHED} past the threshold and we never got a receipt.
     *
     * <p>Distinguished from {@link #SUBMISSION_UNACKNOWLEDGED_TOO_LONG} deliberately, and
     * the distinction falls straight out of the batch state model: this one means we never
     * learned whether the IRS recorded anything, so failure mode B may have fired. The other
     * means the IRS confirmed intake and is merely slow. Those are different problems for a
     * human, and sorting them together would bury the worse one.
     */
    SUBMISSION_INDETERMINATE_TOO_LONG(1),

    /** Retries exhausted. <b>Not terminal</b> &mdash; polling continues at a slow cadence. */
    TRANSMISSION_RETRIES_EXHAUSTED(2),

    /** The IRS rejected a filing, with a reason code. Fix and refile. */
    FILING_REJECTED(3),

    /** Acknowledgment references did not match batch membership. Never guess; flag it. */
    ACK_RECONCILIATION_MISMATCH(3),

    /** A receipt is in hand but acknowledgments are slow. Polling continues. */
    SUBMISSION_UNACKNOWLEDGED_TOO_LONG(4),

    /** A revised export changed data underneath a filing that is already in flight. */
    AMENDED_DATA_FOR_INFLIGHT_FILING(4),

    /** Newly imported data changes a vendor whose filing has already been transmitted. */
    DETERMINATION_CHANGED_AFTER_FILING(4),

    /** A form is required but no TIN was collected. Someone must chase a W-9. */
    VENDOR_MISSING_TIN(5),

    /** Preflight found something the IRS would certainly reject. */
    PREFLIGHT_VALIDATION_FAILED(5),

    /**
     * We received a 429.
     *
     * <p>Severity 1: if our accounting were correct this would be unreachable, so seeing it
     * means the limiter has a bug and we may already have exceeded the budget.
     */
    RATE_BUDGET_BREACH_DETECTED(1),

    /**
     * A filing claims membership in a batch that does not exist.
     *
     * <p>Structurally impossible given the foreign keys. If it ever fires, an invariant is
     * broken and I want to know immediately rather than eventually.
     */
    ORPHANED_BATCH_MEMBERSHIP(0);

    private final int severity;

    AttentionType(int severity) {
        this.severity = severity;
    }

    /** Lower sorts first on the morning-after page. */
    public int severity() {
        return severity;
    }

    /** Whether this indicates a possible correctness failure rather than ordinary work. */
    public boolean isAlarming() {
        return severity <= 1;
    }
}
