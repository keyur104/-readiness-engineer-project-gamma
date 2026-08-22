package com.soraban.readiness.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * "This bean needs a database."
 *
 * <p>A meta-annotation rather than a repeated {@code @ConditionalOnProperty}, for one
 * reason that is not cosmetic: {@code @ConditionalOnProperty} is not repeatable, so a class
 * that already gates on something else &mdash; {@code StubIrsClient} gates on
 * {@code irs.client} &mdash; cannot also gate on the database switch without this.
 *
 * <p>That gap is how {@code seed} broke. It runs with {@code readiness.database.enabled=false}
 * and no {@code DataSource}, but the stub's beans were selected by {@code irs.client} alone,
 * so Spring tried to build a {@code StubStore} and failed on a missing {@code JdbcTemplate}
 * &mdash; in a command that touches no database at all.
 *
 * <p>Deliberately a <b>property</b> condition and not {@code @ConditionalOnBean}: bean
 * conditions on a component-scanned class are evaluated before auto-configuration has
 * created the {@code DataSource}, so they silently evaluate false and remove the bean with
 * no error. That is exactly how {@code RlsGuard} was silently excluded once already (D36).
 * Property conditions read from the Environment and do not depend on bean ordering.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnDatabase {
}
