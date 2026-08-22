package com.soraban.readiness.security;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * The same redaction as {@link TinMaskingConverter}, applied to rendered stack traces.
 *
 * <p>Masking the log message alone would leave the larger hole open. Exception messages
 * are one of the most common ways sensitive values escape &mdash;
 * {@code new IllegalArgumentException("bad TIN: " + value)} is a natural thing to write,
 * a JDBC driver may echo a parameter, and a CSV parser may quote the offending line. All
 * of that surfaces through the throwable, not through the message.
 *
 * <p>Registered as {@code %maskedEx} in {@code logback-spring.xml}.
 */
public class TinMaskingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(ch.qos.logback.classic.spi.IThrowableProxy tp) {
        return TinMaskingConverter.mask(super.throwableProxyToString(tp));
    }

    @Override
    public String convert(ILoggingEvent event) {
        return TinMaskingConverter.mask(super.convert(event));
    }
}
