package com.soraban.readiness.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The TIN protections, checked without a database or a Spring context.
 *
 * <p>For a sole proprietor the TIN <em>is</em> their social security number, which is what makes
 * this the most sensitive column in the schema. The design makes four claims about it, and each
 * one is only worth as much as its test:
 *
 * <ol>
 *   <li>a TIN cannot reach a log through the path that actually leaks &mdash; {@code toString()};</li>
 *   <li>the blind index is firm-scoped, so a leaked column cannot be correlated across firms;</li>
 *   <li>the ciphertext is bound to its row, so copying it elsewhere fails rather than
 *       mis-attributing;</li>
 *   <li>encryption and the blind index use <b>separate keys</b>, so compromising the one that
 *       must be present for every {@code GROUP BY} decrypts nothing.</li>
 * </ol>
 *
 * <p>Runs in milliseconds, which matters: a security check that only runs as part of a slow
 * integration suite is a check people learn to skip.
 */
class TinProtectionTest {

    private static final long FIRM_A = 1L;
    private static final long FIRM_B = 2L;
    private static final long CLIENT = 77L;

    private final TinCryptoService crypto = new TinCryptoService(devProperties());

    // =================================================================================
    // 1. toString() is the leak path that actually gets used
    // =================================================================================

    @Test
    @DisplayName("a TIN never prints itself, however it is interpolated")
    void toStringMasksRatherThanRevealing() {
        Tin tin = Tin.parse("123456789", "SSN").tin();

        // These are the four ways it actually happens in practice: string concatenation, a
        // logging placeholder, an exception message, and String.valueOf via a collection.
        // All of them go through toString(), which is why toString() is where the defence has
        // to be rather than at each call site.
        assertThat(tin.toString()).doesNotContain("123456789");
        assertThat("vendor tin: " + tin).doesNotContain("123456789");
        assertThat(String.format("%s", tin)).doesNotContain("123456789");
        assertThat(java.util.List.of(tin).toString()).doesNotContain("123456789");
        assertThat(new IllegalArgumentException("bad TIN: " + tin).getMessage())
                .doesNotContain("123456789");

        // Last four remain, because a person disambiguating two vendors needs them and four
        // digits is not identifying on its own.
        assertThat(tin.toString()).contains("6789");
        assertThat(tin.last4()).isEqualTo("6789");

        // The one sanctioned way out, named so that "where does plaintext exist" is a
        // greppable question rather than an archaeology exercise.
        assertThat(tin.plaintextForTransmission()).isEqualTo("123456789");
    }

    @Test
    @DisplayName("the Logback backstop masks anything nine-digit-shaped that slips through")
    void theMaskingConverterCatchesRawStrings() {
        // Explicitly a BACKSTOP, not the primary control. The primary control is that a TIN is
        // a value object that cannot print itself; this catches the case where someone logs a
        // raw String they got from a CSV before it ever became a Tin.
        assertThat(TinMaskingConverter.mask("vendor tin 123456789 rejected"))
                .doesNotContain("123456789");
        assertThat(TinMaskingConverter.mask("tin=123-45-6789"))
                .doesNotContain("123-45-6789");

        // And it must not corrupt things that merely look numeric. A masker that ate amounts
        // or row counts would be removed within a week, which is the real failure mode for
        // defences that are noisy.
        assertThat(TinMaskingConverter.mask("imported 497113 rows in 62016 ms"))
                .isEqualTo("imported 497113 rows in 62016 ms");
    }

    // =================================================================================
    // 2. The blind index is firm-scoped
    // =================================================================================

    @Test
    @DisplayName("the same TIN at two firms produces different blind indexes")
    void theBlindIndexCannotBeCorrelatedAcrossFirms() {
        Tin tin = Tin.parse("123456789", "SSN").tin();

        byte[] atFirmA = crypto.blindIndex(FIRM_A, tin);
        byte[] atFirmB = crypto.blindIndex(FIRM_B, tin);

        // The same contractor working for two firms is ordinary. If the blind index were a
        // plain HMAC of the TIN, a leaked column would let anyone holding both firms' data
        // join them -- revealing a commercial relationship neither firm agreed to disclose.
        // Putting firm_id in the HMAC message removes that without costing anything: grouping
        // only ever happens within a firm anyway.
        assertThat(atFirmA).isNotEqualTo(atFirmB);

        // Deterministic within a firm, or GROUP BY would not work at all.
        assertThat(crypto.blindIndex(FIRM_A, tin)).isEqualTo(atFirmA);
        assertThat(atFirmA).hasSize(32);
    }

    @Test
    @DisplayName("different TINs at one firm produce different blind indexes")
    void theBlindIndexDistinguishesVendors() {
        Tin one = Tin.parse("111111111", "EIN").tin();
        Tin two = Tin.parse("222222222", "EIN").tin();

        // Vendor identity is built on this. A collision would silently merge two contractors
        // into one 1099 -- one of them under-reported, the other reported under someone else's
        // identity, and no error anywhere.
        assertThat(crypto.blindIndex(FIRM_A, one))
                .isNotEqualTo(crypto.blindIndex(FIRM_A, two));
    }

    // =================================================================================
    // 3. Ciphertext is bound to its row
    // =================================================================================

    @Test
    @DisplayName("a TIN encrypts and decrypts within its own row")
    void roundTripsWithinTheRowItBelongsTo() {
        Tin tin = Tin.parse("456789123", "EIN").tin();
        byte[] bidx = crypto.blindIndex(FIRM_A, tin);

        TinCryptoService.Encrypted sealed = crypto.encrypt(FIRM_A, CLIENT, bidx, tin);
        Tin recovered = crypto.decrypt(FIRM_A, CLIENT, bidx, sealed.ciphertext(), sealed.keyVersion());

        assertThat(recovered.plaintextForTransmission()).isEqualTo("456789123");

        // Not deterministic: two encryptions of the same TIN differ, because GCM uses a fresh
        // nonce. Equality-searching is the blind index's job precisely so the ciphertext does
        // not have to leak equality as well.
        TinCryptoService.Encrypted again = crypto.encrypt(FIRM_A, CLIENT, bidx, tin);
        assertThat(sealed.ciphertext()).isNotEqualTo(again.ciphertext());
    }

    @Test
    @DisplayName("a ciphertext copied to another row, client or firm fails to decrypt")
    void additionalAuthenticatedDataBindsTheCiphertextToItsRow() {
        Tin tin = Tin.parse("456789123", "EIN").tin();
        byte[] bidx = crypto.blindIndex(FIRM_A, tin);
        TinCryptoService.Encrypted sealed = crypto.encrypt(FIRM_A, CLIENT, bidx, tin);

        // The AAD binds firm | client | blind index. Without it, a ciphertext moved between
        // vendor rows would decrypt cleanly and attach one contractor's TIN to another's
        // name -- a wrong tax form that looks entirely plausible, with no error to notice.
        // GCM turns that from a silent mis-attribution into a failure.
        assertThatThrownBy(() ->
                crypto.decrypt(FIRM_B, CLIENT, bidx, sealed.ciphertext(), sealed.keyVersion()))
                .as("another firm's row")
                .isInstanceOf(Exception.class);

        assertThatThrownBy(() ->
                crypto.decrypt(FIRM_A, CLIENT + 1, bidx, sealed.ciphertext(), sealed.keyVersion()))
                .as("another client's row")
                .isInstanceOf(Exception.class);

        byte[] otherBidx = crypto.blindIndex(FIRM_A, Tin.parse("999999999", "EIN").tin());
        assertThatThrownBy(() ->
                crypto.decrypt(FIRM_A, CLIENT, otherBidx, sealed.ciphertext(), sealed.keyVersion()))
                .as("another vendor's row")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a tampered ciphertext is rejected rather than decrypted to something else")
    void gcmDetectsModification() {
        Tin tin = Tin.parse("456789123", "EIN").tin();
        byte[] bidx = crypto.blindIndex(FIRM_A, tin);
        TinCryptoService.Encrypted sealed = crypto.encrypt(FIRM_A, CLIENT, bidx, tin);

        byte[] tampered = Arrays.copyOf(sealed.ciphertext(), sealed.ciphertext().length);
        tampered[tampered.length - 1] ^= 0x01;

        // Authenticated encryption, not just encryption. A mode without integrity would let a
        // single flipped bit in a backup produce a different, valid-looking nine digits.
        assertThatThrownBy(() ->
                crypto.decrypt(FIRM_A, CLIENT, bidx, tampered, sealed.keyVersion()))
                .isInstanceOf(Exception.class);
    }

    // =================================================================================
    // 4. Two keys, doing two different jobs
    // =================================================================================

    @Test
    @DisplayName("the blind-index key does not decrypt, so leaking it reveals no TIN")
    void theGroupingKeyIsNotTheEncryptionKey() {
        Tin tin = Tin.parse("456789123", "EIN").tin();
        byte[] bidx = crypto.blindIndex(FIRM_A, tin);
        TinCryptoService.Encrypted sealed = crypto.encrypt(FIRM_A, CLIENT, bidx, tin);

        // Build a service whose ENCRYPTION key is the blind-index key: this is what an attacker
        // holding only the grouping key can attempt. The blind-index key is the more exposed of
        // the two, because it must be present for every GROUP BY over a million rows -- so the
        // separation is what stops that exposure becoming a disclosure.
        TinCryptoService withWrongKey = new TinCryptoService(
                new TinProperties(1, java.util.Map.of(1, BLIND_INDEX_KEY), BLIND_INDEX_KEY));

        assertThatThrownBy(() ->
                withWrongKey.decrypt(FIRM_A, CLIENT, bidx, sealed.ciphertext(), sealed.keyVersion()))
                .as("the grouping key must not open the ciphertext")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a name blind index is also firm-scoped, for the no-TIN grouping path")
    void nameGroupingIsScopedToo() {
        // Vendors with no TIN are grouped by normalised name, and that index has exactly the
        // same cross-firm correlation problem as the TIN one.
        assertThat(crypto.nameBlindIndex(FIRM_A, "acme plumbing"))
                .isNotEqualTo(crypto.nameBlindIndex(FIRM_B, "acme plumbing"));
        assertThat(crypto.nameBlindIndex(FIRM_A, "acme plumbing"))
                .isEqualTo(crypto.nameBlindIndex(FIRM_A, "acme plumbing"));
    }

    // =================================================================================
    // Parsing: what counts as usable
    // =================================================================================

    @Test
    @DisplayName("a malformed TIN is kept and flagged, never guessed at or discarded")
    void malformedTinsAreNeitherRepairedNorDropped() {
        Tin.Parsed tooShort = Tin.parse("12345", "EIN");

        // Not repaired: padding it to nine digits would invent an identity. Not discarded:
        // that would delete the filing obligation. Kept, flagged, and blocked from transmission
        // so a person resolves it -- the design's rule that uncertainty fails toward a human.
        assertThat(tooShort.isPresent()).isFalse();
        assertThat(tooShort.blocksTransmission()).isTrue();
        assertThat(tooShort.rawIfMalformed()).isNotNull();

        // Formatting is normalised, because "12-3456789" and "123456789" are the same TIN and
        // grouping must not depend on how a bookkeeper typed it.
        assertThat(Tin.parse("12-3456789", "EIN").tin().plaintextForTransmission())
                .isEqualTo("123456789");

        // A 000-prefixed TIN is structurally invalid and is caught before it can burn a call
        // from a 20-per-minute budget on a rejection we could have predicted.
        assertThat(Tin.parse("000123456", "SSN").tin().hasInvalidPrefix()).isTrue();
    }

    // =================================================================================

    private static final String ENCRYPTION_KEY = base64Of("dev-only-tin-key-0123456789abcde");
    private static final String BLIND_INDEX_KEY = base64Of("dev-only-bidx-key-0123456789abcd");

    private static TinProperties devProperties() {
        return new TinProperties(1, java.util.Map.of(1, ENCRYPTION_KEY), BLIND_INDEX_KEY);
    }

    private static String base64Of(String raw) {
        byte[] bytes = Arrays.copyOf(raw.getBytes(StandardCharsets.UTF_8), 32);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
