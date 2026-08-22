package com.soraban.readiness.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Replaces Spring Boot's default transaction manager with {@link FirmTransactionManager},
 * so that <em>every</em> transaction in the application &mdash; web request, CLI command,
 * background worker, scheduled sweep &mdash; carries firm context into the database
 * session without any of them having to remember to.
 *
 * <p>Overriding the transaction manager rather than adding an aspect or an interceptor is
 * deliberate: there is exactly one way to begin a transaction in Spring, so overriding
 * that one point means no code path can accidentally skip the context. An aspect would
 * only cover the methods it was pointcut onto, and the one that got missed would be the
 * one that leaked.
 *
 * <p>Flyway's credentials are not configured here. Boot's own Flyway auto-configuration
 * already reads {@code spring.flyway.url/user/password}, which is how migrations run as
 * {@code readiness_owner} while the runtime pool connects as {@code readiness_app} --
 * two credential sets, no custom wiring.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class DataSourceConfig {

    /**
     * Marked {@link Primary} so it wins over the {@code JdbcTransactionManager} that
     * {@code DataSourceTransactionManagerAutoConfiguration} would otherwise contribute.
     *
     * <p><b>Gated on a property, deliberately not on {@code @ConditionalOnBean(DataSource)}.</b>
     * Bean conditions in a user {@code @Configuration} are evaluated during component
     * scanning, which runs <em>before</em> auto-configuration has contributed the
     * DataSource. The condition would therefore evaluate false, this bean would never be
     * created, and Spring's own {@code JdbcTransactionManager} would quietly take its
     * place &mdash; leaving every transaction without firm context while the application
     * started perfectly and reported nothing. A property condition is evaluated from the
     * Environment and does not depend on bean ordering at all.
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new FirmTransactionManager(dataSource);
    }
}
