package com.soraban.readiness.transmission.domain;

/**
 * What we know about a batch's fate at the IRS.
 *
 * <h2>Why batch state is separate from filing state</h2>
 *
 * <p>A receipt is evidence about a <b>batch</b>; an acknowledgment is evidence about a
 * <b>filing</b>. The submission call returns one receipt for up to 100 filings and says
 * nothing about per-filing acceptance; acknowledgments arrive later, individually.
 *
 * <p>Collapsing the two into one state column is how a system ends up unable to answer
 * "did the IRS get filing #47?" &mdash; because the only thing it recorded was a fact about
 * the envelope. Batch state models <em>what we know about the world</em>; filing state
 * models <em>what we may do next</em>.
 */
public enum BatchState {

    /**
     * Membership and idempotency key committed. <b>Nothing has left this process.</b>
     *
     * <p>The one state from which a batch can be abandoned freely: because no request was
     * ever made, releasing its filings back to {@code READY_TO_TRANSMIT} needs no evidence.
     *
     * <p>There is deliberately no {@code BUILDING} state before this. Planning is a single
     * transaction, so a batch row never exists in a half-assembled form.
     */
    SEALED,

    /**
     * At least one request has left this process for this key. <b>IRS state is UNKNOWN.</b>
     *
     * <p>Entered by a transaction that commits <em>before</em> any byte goes on the wire --
     * the write-ahead barrier. Once here, only evidence <em>from the IRS</em> can move this
     * batch: never an error, never a timeout, never a crash. A 500, a socket reset, and a
     * process kill all leave the batch exactly here, which is correct, because all three
     * are equally uninformative about what the server did.
     */
    DISPATCHED,

    /** A receipt is in hand. IRS state is KNOWN-RECORDED. Awaiting per-filing acks. */
    SUBMITTED,

    /** Every member resolved, accepted or rejected. Terminal. */
    ACKNOWLEDGED,

    /**
     * Reconciliation <b>proved</b> the IRS has no record of this key. Members released,
     * generation bumped. Terminal.
     *
     * <p>The only path by which filings escape {@code SUBMITTED_UNACKNOWLEDGED}, and it
     * requires positive evidence &mdash; a status call returning {@code Unknown}. An error
     * is not evidence of absence.
     */
    VOID;

    /** Whether the IRS might hold this batch's filings. */
    public boolean possiblyRecorded() {
        return this == DISPATCHED || this == SUBMITTED || this == ACKNOWLEDGED;
    }

    /** Whether this batch still needs work from a worker. */
    public boolean isActive() {
        return this == SEALED || this == DISPATCHED || this == SUBMITTED;
    }

    public boolean isTerminal() {
        return this == ACKNOWLEDGED || this == VOID;
    }
}
