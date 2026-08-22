package com.soraban.readiness.security;

import java.util.Objects;

/**
 * A taxpayer identification number.
 *
 * <p>For a sole proprietor the TIN <em>is</em> their Social Security Number, so this is
 * the most sensitive value in the system. The class exists to make the safe rendering the
 * <b>default</b> rendering.
 *
 * <h2>Why this is a class and not a record</h2>
 *
 * <p>Records generate a {@code toString()} that prints every component. For this type
 * that generated method would be a data breach with an auto-generated implementation.
 * {@link #toString()} is overridden here to emit {@code ***-**-6789}, because
 * {@code toString} is the actual leak path in practice &mdash; string concatenation,
 * {@code log.info("{}", vendor)}, Jackson's default serializer, Hibernate parameter
 * tracing, and {@code new IllegalArgumentException("bad TIN: " + value)} all route
 * through it. Making the safe form the default form removes the entire class of mistake
 * rather than relying on every future call site to remember.
 *
 * <h2>Why this is not the storage representation</h2>
 *
 * <p>A {@code Tin} instance holds plaintext and is therefore short-lived by design. At
 * rest a TIN is three columns: {@code tin_ct} (AES-256-GCM ciphertext),
 * {@code tin_bidx} (a keyed HMAC blind index, which is what equality and {@code GROUP BY}
 * actually run against), and {@code tin_last4} for display. See {@link TinCryptoService}.
 *
 * @see TinCryptoService
 */
public final class Tin {

    /** Where a TIN can be in the source data. Drives determination exceptions, not rejections. */
    public enum Status {
        /** Exactly nine digits after normalization. */
        PRESENT,
        /** Absent from the export. The vendor still requires a form; a W-9 needs collecting. */
        MISSING,
        /** Present but not nine digits. Preserved for a human, never used as an identity key. */
        MALFORMED
    }

    /** Structural guess only. The IRS distinguishes these; a bare nine digits does not. */
    public enum Kind {
        EIN, SSN, UNKNOWN
    }

    private final String digits;
    private final Kind kind;

    private Tin(String digits, Kind kind) {
        this.digits = digits;
        this.kind = kind;
    }

    /**
     * Normalizes raw export text into a {@code Tin}, or reports why it could not be.
     *
     * <p>Strips every non-digit, so {@code "12-3456789"}, {@code "123-45-6789"} and
     * {@code "123456789"} all converge. Anything that is not then exactly nine digits is
     * {@link Status#MALFORMED}: we will not aggregate on a value we cannot validate, and
     * we will not discard it either.
     *
     * <p>Note that blank is <b>not</b> an error. A missing TIN does not remove the filing
     * obligation, so it must flow through the importer as ordinary data and surface later
     * as a determination exception. Treating it as a malformed row would silently drop a
     * vendor who still requires a form &mdash; precisely the failure the brief guards
     * against.
     */
    public static Parsed parse(String raw, String declaredType) {
        if (raw == null || raw.isBlank()) {
            return new Parsed(null, Status.MISSING, null);
        }

        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        String normalized = sb.toString();

        if (normalized.length() != 9) {
            return new Parsed(null, Status.MALFORMED, raw.strip());
        }

        return new Parsed(new Tin(normalized, inferKind(raw, declaredType)), Status.PRESENT, null);
    }

    /**
     * Infers EIN vs SSN. The declared column wins when present; otherwise the mask is the
     * only signal, since {@code 12-3456789} (EIN) and {@code 123-45-6789} (SSN) are the
     * same nine digits punctuated differently. Unknown is a perfectly acceptable answer
     * and is not an error &mdash; it does not affect the filing decision.
     */
    private static Kind inferKind(String raw, String declaredType) {
        if (declaredType != null && !declaredType.isBlank()) {
            String t = declaredType.strip().toUpperCase();
            if (t.startsWith("E")) {
                return Kind.EIN;
            }
            if (t.startsWith("S")) {
                return Kind.SSN;
            }
        }
        int firstDash = raw.indexOf('-');
        if (firstDash == 2) {
            return Kind.EIN;   // 12-3456789
        }
        if (firstDash == 3) {
            return Kind.SSN;   // 123-45-6789
        }
        return Kind.UNKNOWN;
    }

    /**
     * The nine digits, in the clear.
     *
     * <p>Named to be conspicuous at the call site and in a diff. There are exactly two
     * legitimate callers &mdash; building an IRS submission payload and an explicitly
     * audited human "reveal" &mdash; and {@code TinAccessArchTest} fails the build if a
     * third appears. Never log this, never concatenate it into a message, never put it in
     * an exception.
     */
    public String plaintextForTransmission() {
        return digits;
    }

    /** The last four digits, which is what every UI and log line shows. */
    public String last4() {
        return digits.substring(5);
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Whether the IRS would reject this outright for beginning {@code 000}.
     *
     * <p>Checked before batching so a filing that is provably going to be rejected never
     * consumes one of the twenty API calls available per minute &mdash; the scarcest
     * resource in the system.
     */
    public boolean hasInvalidPrefix() {
        return digits.startsWith("000");
    }

    /** Masked. This is the only rendering that should ever reach a log, page, or message. */
    @Override
    public String toString() {
        return "***-**-" + last4();
    }

    /**
     * Equality on the plaintext digits.
     *
     * <p>Note that vendor identity in the determination pass does <em>not</em> go through
     * this method &mdash; it groups on the persisted HMAC blind index, so that a million
     * ledger rows can be aggregated by TIN without a single decryption. This exists for
     * in-memory work such as the seed generator's fixture planting.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Tin other && digits.equals(other.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(digits);
    }

    /**
     * The outcome of {@link #parse}: at most one of {@code tin} or {@code rawIfMalformed}
     * is set, and {@code status} says which case you are in.
     *
     * @param tin            the parsed TIN, or {@code null} unless {@code status} is {@link Status#PRESENT}
     * @param status         where this TIN stands
     * @param rawIfMalformed the original text when malformed, preserved so a human can fix it
     */
    public record Parsed(Tin tin, Status status, String rawIfMalformed) {

        public boolean isPresent() {
            return status == Status.PRESENT;
        }

        /** True when the vendor cannot transmit cleanly and a person must intervene. */
        public boolean blocksTransmission() {
            return status != Status.PRESENT;
        }
    }
}
