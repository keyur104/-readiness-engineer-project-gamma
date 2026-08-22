package com.soraban.readiness.security;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts anything that looks like a TIN from log messages, immediately before the
 * appender writes.
 *
 * <h2>This is a net under the trapeze, not the trapeze</h2>
 *
 * <p>Being explicit about that distinction matters, because a regex scrubber presented as
 * the primary control invites people to stop being careful upstream. The actual controls
 * are:
 *
 * <ol>
 *   <li>{@link Tin} renders as {@code ***-**-6789} from {@code toString()}, so the safe
 *       form is the default form at every call site that never thought about it.</li>
 *   <li>The ledger table stores no plaintext TIN at all, so the value simply is not
 *       present in the overwhelming majority of the system.</li>
 *   <li>TINs are always bound as JDBC parameters, and Postgres is configured with
 *       {@code log_parameter_max_length = 0} so bind values never reach the server log.</li>
 * </ol>
 *
 * <p>This converter catches what those miss: a TIN pasted into a message by hand, one
 * arriving inside third-party exception text, or a raw CSV line echoed during import
 * debugging.
 *
 * <h2>Known imprecision, accepted deliberately</h2>
 *
 * <ul>
 *   <li><b>False positives.</b> A bare nine-digit run is masked, which will occasionally
 *       redact an invoice number or a routing number. That is the right side of the trade:
 *       a masked invoice number costs someone thirty seconds, a leaked SSN is a
 *       disclosure incident.</li>
 *   <li><b>False negatives.</b> A TIN split across two log arguments, or reformatted in a
 *       way this does not anticipate, gets through. Nothing regex-based can fix that,
 *       which is precisely why it is not the primary control.</li>
 * </ul>
 *
 * <p>Registered as a {@code %maskedMsg} conversion word in {@code logback-spring.xml}.
 */
public class TinMaskingConverter extends MessageConverter {

    /**
     * Ordered most-specific first so the punctuated forms win before the bare-digits
     * rule sees them.
     *
     * <ul>
     *   <li>{@code 123-45-6789} -- SSN formatting</li>
     *   <li>{@code 12-3456789} -- EIN formatting</li>
     *   <li>{@code 123456789} -- unpunctuated, bounded so it does not match inside a
     *       longer digit run (an amount in cents, a bigint id, a timestamp)</li>
     * </ul>
     */
    private static final Pattern SSN_FORMAT = Pattern.compile("(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])");
    private static final Pattern EIN_FORMAT = Pattern.compile("(?<![\\d-])\\d{2}-\\d{7}(?![\\d-])");
    private static final Pattern BARE_NINE  = Pattern.compile("(?<!\\d)\\d{9}(?!\\d)");

    private static final String MASK_PREFIX = "***-**-";

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    /**
     * Replaces each match with {@code ***-**-} plus the trailing four digits, so a log
     * line stays diagnostically useful. Last four is what the UI shows anyway, and it is
     * what a human uses to confirm they are looking at the right vendor.
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = replaceKeepingLast4(text, SSN_FORMAT);
        result = replaceKeepingLast4(result, EIN_FORMAT);
        result = replaceKeepingLast4(result, BARE_NINE);
        return result;
    }

    private static String replaceKeepingLast4(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;   // overwhelmingly the common path; avoid allocating a buffer
        }

        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        do {
            out.append(text, cursor, matcher.start());
            String matched = matcher.group();
            out.append(MASK_PREFIX).append(matched.substring(matched.length() - 4));
            cursor = matcher.end();
        } while (matcher.find());
        out.append(text, cursor, text.length());
        return out.toString();
    }
}
