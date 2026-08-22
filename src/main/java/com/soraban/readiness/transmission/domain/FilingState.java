package com.soraban.readiness.transmission.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a filing stands in its lifecycle. Every filing is in exactly one of these.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>There is no {@code FAILED}, no {@code ABANDONED}, no {@code MAX_RETRIES_EXCEEDED}, and
 * no {@code ERROR}. The brief is explicit that <em>"we stopped retrying" is not a terminal
 * state &mdash; it's something a human gets shown</em>, so it is not a state at all. It is
 * an {@link AttentionType#TRANSMISSION_RETRIES_EXHAUSTED} attention item raised against a
 * filing that remains exactly where it was, still scheduled, still polling.
 *
 * <p>Modelling human-attention conditions as states would force a choice between knowing
 * <em>where</em> a filing is and knowing <em>why</em> someone is needed. Keeping them
 * orthogonal means both are answerable at once.
 *
 * <h2>The load-bearing definition</h2>
 *
 * <p>{@link #SUBMITTED_UNACKNOWLEDGED} means <b>"the IRS may have this filing; we must not
 * send it under a new key."</b>
 *
 * <p>It is a statement about our <em>epistemic position</em>, not about what we observed.
 * It covers both "we hold a receipt" and "our request errored and we have no idea what
 * happened". Under failure mode B those two situations are <b>indistinguishable at the
 * moment they occur</b> &mdash; the filings are live and the receipt was never delivered --
 * so they must map to the same filing state. Any design that gives them different states
 * has already lost, because it will eventually act on a distinction it cannot actually make.
 */
public enum FilingState {

    /** Determination produced it; not yet eligible to transmit. */
    DRAFT,

    /** Preflight passed. Eligible; awaiting a batch. */
    READY_TO_TRANSMIT,

    /**
     * Structurally untransmittable &mdash; missing TIN, malformed TIN, non-positive amount.
     *
     * <p>The obligation still exists and the filing is still counted and visible. This is
     * not "skipped": the brief is explicit that a missing TIN must never be a reason to
     * silently drop a vendor. It is a filing waiting on a person.
     */
    BLOCKED,

    /**
     * Claimed by a SEALED batch. Provably not yet sent.
     *
     * <p>The only state from which a filing can be released without evidence, because
     * SEALED means nothing has left the process.
     */
    BATCHED,

    /** The IRS may have this. See the class note &mdash; this is the state that matters. */
    SUBMITTED_UNACKNOWLEDGED,

    /** Acknowledged as accepted, with an IRS record identifier. The only terminal state. */
    ACCEPTED,

    /** Acknowledged as rejected, with a reason code. Awaits a human; not terminal. */
    REJECTED;

    /**
     * States from which a filing may still be selected into a batch.
     *
     * <p>Note that {@link #SUBMITTED_UNACKNOWLEDGED} is not here, and that is the entire
     * point: a filing the IRS may already hold is never eligible for a new batch until
     * reconciliation proves otherwise and bumps its generation.
     */
    public static final Set<FilingState> TRANSMITTABLE = EnumSet.of(READY_TO_TRANSMIT);

    /**
     * States a re-determination pass is allowed to overwrite.
     *
     * <p>The freeze rule: sealing a batch freezes content. Once a filing is BATCHED or
     * SUBMITTED_UNACKNOWLEDGED, a revised export arriving at 2 a.m. must not mutate the
     * amount, because the idempotency key was derived from the old content &mdash; we would
     * ship one number and record another.
     */
    public static final Set<FilingState> MUTABLE_BY_DETERMINATION =
            EnumSet.of(DRAFT, READY_TO_TRANSMIT, BLOCKED);

    /** Whether the IRS may hold this filing. */
    public boolean possiblyLive() {
        return this == SUBMITTED_UNACKNOWLEDGED || this == ACCEPTED || this == REJECTED;
    }

    /** {@link #ACCEPTED} alone. Everything else can still change. */
    public boolean isTerminal() {
        return this == ACCEPTED;
    }

    /** Whether a person must act before this filing can progress. */
    public boolean needsHuman() {
        return this == BLOCKED || this == REJECTED;
    }
}
