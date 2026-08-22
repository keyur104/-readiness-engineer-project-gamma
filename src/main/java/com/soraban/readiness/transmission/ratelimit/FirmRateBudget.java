package com.soraban.readiness.transmission.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Enforces the IRS rate budget: 20 calls per rolling 60 seconds per firm, shared across
 * submissions and status checks, across all clients, all worker threads, all processes, and
 * <b>across restarts</b>.
 *
 * <h2>A sliding-window log, not a token bucket</h2>
 *
 * <p>The token bucket is the obvious choice and it is wrong here, in a way worth being able
 * to explain: a bucket with capacity 20 refilling at 20/60s permits 20 calls at
 * <em>t=0</em> and one more at <em>t=3s</em>. The rolling window [0, 60] now contains
 * <b>21</b>. A token bucket whose burst equals its capacity does not implement a rolling
 * window &mdash; it implements an <em>average</em>.
 *
 * <p>Making a bucket safe requires burst = 1, i.e. one call every three seconds. That is
 * correct but drains a backlog far more slowly than the budget actually allows, and on
 * February 1 throughput is the scarce resource. A log implements the stated semantics
 * exactly: "20 calls per rolling 60 seconds" <em>is</em>
 * {@code count(*) over the last 60 seconds < 20}. There is no approximation to defend.
 *
 * <h2>Why it lives in PostgreSQL</h2>
 *
 * <ul>
 *   <li><b>Restart-safety is structural, not achieved.</b> There is no in-memory state to
 *       lose. An in-memory limiter that restarts mid-window permits an immediate fresh burst
 *       of 20 <em>on top of</em> the 20 it already spent &mdash; exactly double the budget,
 *       at exactly the moment (a crash) when things are already going wrong.</li>
 *   <li><b>Consumption commits with the state change.</b> This is the decisive advantage
 *       over Redis. The token is spent in the <em>same transaction</em> that moves the batch
 *       to {@code DISPATCHED}, so there is no window in which one exists without the other.
 *       If the process dies immediately after commit, we have spent a token without making a
 *       call &mdash; we <b>under</b>-use the budget, which is the safe direction. No ordering
 *       over-uses it.</li>
 *   <li>It doubles as the compliance audit trail the brief's security section asks for.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class FirmRateBudget {

    private final JdbcTemplate jdbc;
    private final int limit;
    private final Duration window;

    /**
     * How much wider our window is than the budget we are promising to respect.
     *
     * <p><b>We and the endpoint do not timestamp the same instant.</b> Our token is consumed
     * inside T2, before a byte goes on the wire; the endpoint stamps the call when the request
     * <em>arrives</em>. Arrival is always later, and later by a variable amount.
     *
     * <p>So consider the oldest call in a full window. On our clock it may have just aged past
     * 60 seconds, freeing a slot. On the endpoint's clock it may not have — if that old call
     * happened to reach them more slowly than the new one does, their gap is shorter than ours
     * and they see 21 calls in their 60 seconds.
     *
     * <p>This is not hypothetical. A full acceptance run produced exactly that: 21 calls in one
     * of the stub's 60-second windows, with the oldest <b>59.982 s</b> before the newest. We
     * were 18 milliseconds optimistic, and the endpoint's clock is the one that counts.
     *
     * <p>The fix is to be deliberately pessimistic: count over a slightly WIDER window than the
     * one we promise. A call we exclude is then old enough that no plausible delivery skew can
     * pull it back inside the endpoint's window. The cost is a fractionally lower throughput,
     * which is the same direction the rest of this design already errs in &mdash; a crash after
     * T2 commits under-uses the budget too, and under-using it is always survivable.
     *
     * <p><b>The margin is the GREATER of a ratio and an absolute floor</b>, and the floor is
     * the part that matters. A pure ratio was the first attempt and it was wrong: delivery
     * jitter is an absolute quantity — scheduling delay, a GC pause, a transaction commit —
     * measured in milliseconds, not a fraction of whatever window happens to be configured.
     * At the production 60-second window 2% is 1.2 s and ample; at the test profile's 500 ms
     * window it is 10 ms, which covers nothing. The violation duly reappeared in the
     * kill-a-real-child-process test, where the jitter is largest because it spans processes.
     *
     * <p>So the ratio keeps the margin sensible as the window grows, and the floor keeps it
     * meaningful when the window is small.
     */
    private final double safetyMarginRatio;

    /** Absolute lower bound on the margin, covering delivery jitter at any window size. */
    private final Duration safetyMarginFloor;

    /** The window actually counted over: the promised budget plus the skew margin. */
    private final long effectiveWindowMillis;

    public FirmRateBudget(JdbcTemplate jdbc,
                          @org.springframework.beans.factory.annotation.Value("${irs.rate.limit:20}")
                          int limit,
                          @org.springframework.beans.factory.annotation.Value("${irs.rate.window:60s}")
                          Duration window,
                          @org.springframework.beans.factory.annotation.Value("${irs.rate.safety-margin-ratio:0.02}")
                          double safetyMarginRatio,
                          @org.springframework.beans.factory.annotation.Value("${irs.rate.safety-margin-floor:250ms}")
                          Duration safetyMarginFloor) {
        this.jdbc = jdbc;
        this.limit = limit;
        this.window = window;
        this.safetyMarginRatio = safetyMarginRatio;
        this.safetyMarginFloor = safetyMarginFloor;
        this.effectiveWindowMillis = window.toMillis()
                + Math.max(Math.round(window.toMillis() * safetyMarginRatio),
                           safetyMarginFloor.toMillis());
    }

    /** Granted with the call recorded, or refused with the instant a slot frees up. */
    public sealed interface Admission {

        record Granted(long callLogId) implements Admission {
        }

        record Refused(Instant retryAt) implements Admission {
        }

        default boolean isGranted() {
            return this instanceof Granted;
        }
    }

    /**
     * Attempts to admit one outbound call.
     *
     * <p><b>Must be invoked inside the caller's transaction</b> &mdash; that is the whole
     * design. The advisory lock is transaction-scoped, so it releases on commit, rollback,
     * <em>or crash</em>, with no cleanup path to get wrong; and the inserted row commits
     * atomically with whatever state change the call is part of.
     *
     * <p>The lock serializes admissions for one firm, which is what closes the
     * check-then-act race between counting the window and inserting into it. At twenty
     * calls a minute per firm, contention is not a consideration &mdash; it is held for a
     * couple of milliseconds and never across the HTTP call itself.
     *
     * @param callType {@code SUBMIT} or {@code STATUS}
     */
    public Admission tryAdmit(long firmId, String callType, java.util.UUID batchId, String workerId) {
        // Auto-released on commit, rollback, or backend death. No cleanup code exists
        // because none is needed.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class,
                "irs_rate:" + firmId);

        // clock_timestamp(), NOT now(). now() is transaction-START time and stays frozen for
        // the transaction's duration, so a long transaction would backdate its own calls and
        // silently widen the window. Small detail, real bug.
        // effectiveWindowMillis, not window.toMillis() -- see the field's note. Counting over
        // exactly the promised window makes us right on our own clock and occasionally wrong on
        // the endpoint's, which is the only clock that can throttle us.
        Long used = jdbc.queryForObject("""
                select count(*) from app.irs_call_log
                 where called_at > clock_timestamp() - (? || ' milliseconds')::interval
                """, Long.class, effectiveWindowMillis);

        if (used != null && used >= limit) {
            Instant retryAt = jdbc.queryForObject("""
                    select min(called_at) + (? || ' milliseconds')::interval
                      from app.irs_call_log
                     where called_at > clock_timestamp() - (? || ' milliseconds')::interval
                    """, Instant.class, effectiveWindowMillis, effectiveWindowMillis);
            return new Admission.Refused(
                    retryAt != null ? retryAt : Instant.now().plus(window));
        }

        Long id = jdbc.queryForObject("""
                insert into app.irs_call_log (firm_id, call_type, batch_id, worker_id)
                values (app.current_firm_id(), ?, ?, ?)
                returning id
                """, Long.class, callType, batchId, workerId);

        return new Admission.Granted(id == null ? 0L : id);
    }

    /**
     * Records what became of an admitted call.
     *
     * <p>Note there is no "refund" path. A token is consumed at admission and never returned,
     * even when the call fails &mdash; a failed call still consumed real capacity at the
     * endpoint, so refunding it would let a run of failures exceed the budget.
     *
     * <p>A refund for the crash-after-commit case was considered and rejected: it saves at
     * most one token per crash and re-introduces precisely the two-step ambiguity the whole
     * design exists to eliminate.
     */
    public void recordOutcome(long callLogId, String outcome) {
        jdbc.update("update app.irs_call_log set outcome = ? where id = ?", outcome, callLogId);
    }

    /** How many calls remain in the current window. For the poller's priority decision. */
    public int remaining(long firmId) {
        Long used = jdbc.queryForObject("""
                select count(*) from app.irs_call_log
                 where called_at > clock_timestamp() - (? || ' milliseconds')::interval
                """, Long.class, window.toMillis());
        return Math.max(0, limit - (used == null ? 0 : used.intValue()));
    }

    /** Status calls admitted in the current window, for the reserved-share floor. */
    public int statusCallsInWindow(long firmId) {
        Long used = jdbc.queryForObject("""
                select count(*) from app.irs_call_log
                 where call_type = 'STATUS'
                   and called_at > clock_timestamp() - (? || ' milliseconds')::interval
                """, Long.class, window.toMillis());
        return used == null ? 0 : used.intValue();
    }

    /**
     * The stub's independent view of whether we ever breached the budget.
     *
     * <p>Deliberately queries {@code irs_stub.call_log} rather than our own accounting.
     * Checking our own would prove only that our accounting is self-consistent; checking the
     * endpoint's proves we never actually exceeded it. Used by the invariant checker and by
     * the restart test.
     *
     * @return the largest number of calls observed in any rolling window, per firm
     */
    public Optional<Integer> observedPeakAtEndpoint() {
        Integer peak = jdbc.queryForObject("""
                select max(c) from (
                  select count(*) over (
                           partition by firm_id order by at
                           range between interval '60 seconds' preceding and current row) as c
                    from irs_stub.call_log) w
                """, Integer.class);
        return Optional.ofNullable(peak);
    }

    public int limit() {
        return limit;
    }

    public Duration window() {
        return window;
    }
}
