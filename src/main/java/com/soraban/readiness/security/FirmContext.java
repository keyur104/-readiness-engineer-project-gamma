package com.soraban.readiness.security;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The current firm, for the current thread, for the duration of one unit of work.
 *
 * <p>Read by {@link com.soraban.readiness.config.FirmTransactionManager} at transaction
 * start and pushed into the database session as a transaction-local setting, where
 * PostgreSQL Row-Level Security uses it to filter every statement.
 *
 * <h2>Why this is a plain ThreadLocal and not an InheritableThreadLocal</h2>
 *
 * <p>Inheritance would be actively dangerous here. A child thread &mdash; an
 * {@code @Async} task, a parallel stream, a worker spawned mid-request &mdash; would
 * silently inherit whichever firm happened to be current when the pool thread was
 * <em>created</em>, which is not necessarily the firm it is working for. That is a leak
 * that looks like correct code.
 *
 * <p>So context never crosses a thread boundary implicitly. Anything that needs it on
 * another thread is handed it explicitly, via a task decorator or an explicit
 * {@link #runAs} at the top of the worker loop. Losing the context fails loudly
 * (SQLState {@code 28000} from {@code app.current_firm_id()}); inheriting the wrong one
 * would fail silently. We take the loud failure every time.
 *
 * <h2>Where the value comes from</h2>
 *
 * <p>The authenticated principal (web) or a mandatory {@code --firm} argument (CLI).
 * Never a URL path variable, form field, query parameter, or header. A consequence
 * worth stating: an IDOR attempt against another firm's client id returns 404 for free,
 * because RLS makes the row genuinely not exist for that transaction.
 */
public final class FirmContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private FirmContext() {
    }

    /** The current firm, or empty when running outside any firm scope. */
    public static Optional<Long> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current firm id, or {@code null}. Used by the transaction manager. */
    public static Long currentOrNull() {
        return CURRENT.get();
    }

    /**
     * The current firm id, or a hard failure. For code that has no meaningful
     * behaviour without a firm and should not quietly do nothing.
     */
    public static long require() {
        Long firmId = CURRENT.get();
        if (firmId == null) {
            throw new IllegalStateException(
                    "no firm context on this thread; wrap the work in FirmContext.runAs(firmId, ...)");
        }
        return firmId;
    }

    /**
     * Runs {@code body} with {@code firmId} as the current firm, restoring whatever was
     * previously set. Restoring the previous value rather than clearing it keeps nesting
     * honest &mdash; an inner scope for a different firm cannot strip the outer one.
     */
    public static <T> T runAs(long firmId, Supplier<T> body) {
        Long previous = CURRENT.get();
        CURRENT.set(firmId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@link #runAs(long, Supplier)} for work that returns nothing. */
    public static void runAs(long firmId, Runnable body) {
        runAs(firmId, () -> {
            body.run();
            return null;
        });
    }

    /** {@link #runAs(long, Supplier)} for work that throws checked exceptions. */
    public static <T> T callAs(long firmId, Callable<T> body) throws Exception {
        Long previous = CURRENT.get();
        CURRENT.set(firmId);
        try {
            return body.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Clears the context. Only for filter/worker teardown in a {@code finally} block;
     * ordinary code should use {@link #runAs} so the restore is automatic.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
