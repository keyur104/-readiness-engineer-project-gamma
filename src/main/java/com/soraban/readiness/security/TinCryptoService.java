package com.soraban.readiness.security;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Encrypts TINs at rest and derives the blind index that vendor identity groups on.
 *
 * <h2>Why application-level and not pgcrypto</h2>
 *
 * <p>{@code pgp_sym_encrypt(tin, 'key')} places the key in the SQL statement text, where
 * it lands in {@code pg_stat_activity}, {@code pg_stat_statements}, and any
 * {@code log_statement} or {@code log_min_duration_statement} output. The database would
 * then hold both the ciphertext and, in its own logs, the key that opens it. Encrypting
 * in Java means a stolen {@code pg_dump} &mdash; the realistic threat for a firm running
 * its own Postgres &mdash; is inert. Full-disk encryption does not help here at all: it
 * protects a stolen drive, not a dump, a replica, or a backup bucket.
 *
 * <h2>Why a blind index rather than deterministic encryption</h2>
 *
 * <p>Part 2 requires that vendors be identified by TIN, which means grouping roughly a
 * million ledger rows by TIN. That has to be an index-friendly equality operation, not a
 * decrypt-per-row. Both deterministic encryption and a keyed HMAC leak equality &mdash;
 * that is the unavoidable price of {@code GROUP BY} &mdash; but the HMAC wins on three
 * counts: it uses a <em>separate key</em> from encryption, so compromising the grouping
 * key decrypts nothing; it is one-way, so the column is not a decryption oracle; and it
 * is a fixed 32 bytes that indexes like any other {@code bytea}.
 *
 * <h2>The honest limitation</h2>
 *
 * <p>The TIN space is 10<sup>9</sup>. If {@code blindIndexKey} ever leaks alongside the
 * database, the entire index column is recoverable by brute force in seconds. The only
 * real mitigation is that the key never touches the database and never appears in a
 * committed config file. Truncating the index to force collisions would blunt an offline
 * attack, but it would also merge genuinely distinct vendors and so break the correctness
 * of determination &mdash; not a trade worth making at this scale. This is stated plainly
 * in the write-up rather than presenting the blind index as stronger than it is.
 *
 * <h2>Domain separation and integrity binding</h2>
 *
 * <ul>
 *   <li>The blind index message includes {@code firm_id}, so the same contractor working
 *       for both firms produces different index values. A leaked column cannot be used to
 *       correlate vendors across firms &mdash; free, and it reinforces the isolation
 *       story.</li>
 *   <li>GCM's additional authenticated data binds the ciphertext to
 *       {@code firm_id ‖ client_id ‖ blind_index}. A ciphertext copied from one vendor row
 *       to another fails to decrypt rather than silently mis-attributing a TIN to the
 *       wrong contractor. Cheap integrity for a value with legal consequences.</li>
 * </ul>
 */
@Service
public class TinCryptoService {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String MAC = "HmacSHA256";
    private static final int NONCE_BYTES = 12;      // GCM standard; 96-bit nonces avoid rehashing
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;        // AES-256

    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKeySpec> encryptionKeys;
    private final SecretKeySpec blindIndexKey;
    private final int activeKeyVersion;

    public TinCryptoService(TinProperties properties) {
        this.activeKeyVersion = properties.activeKeyVersion();
        this.blindIndexKey = new SecretKeySpec(decodeKey(properties.blindIndexKey(), "blind-index-key"), MAC);

        Map<Integer, SecretKeySpec> keys = new HashMap<>();
        properties.keys().forEach((version, encoded) ->
                keys.put(version, new SecretKeySpec(decodeKey(encoded, "keys[" + version + "]"), "AES")));
        this.encryptionKeys = Map.copyOf(keys);
    }

    private static byte[] decodeKey(String base64, String name) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("readiness.tin." + name + " is not valid base64", e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "readiness.tin." + name + " must decode to " + KEY_BYTES + " bytes, got " + key.length);
        }
        return key;
    }

    /**
     * The searchable, non-reversible representation of a TIN, scoped to one firm.
     *
     * <p>This is what {@code ledger_line} and {@code vendor} group and join on. The ledger
     * table stores <b>no plaintext TIN at all</b> &mdash; keeping the most sensitive value
     * in the system out of the million-row table is a more effective control than any
     * amount of cryptography applied to it.
     */
    public byte[] blindIndex(long firmId, Tin tin) {
        try {
            Mac mac = Mac.getInstance(MAC);
            mac.init(blindIndexKey);
            mac.update(Long.toString(firmId).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) ':');
            mac.update(tin.plaintextForTransmission().getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("blind index computation failed", e);
        }
    }

    /**
     * Blind index for a vendor that has no TIN, keyed by normalized name instead.
     *
     * <p>Uses a distinct domain prefix so a name-derived key can never collide with a
     * TIN-derived one. Without the prefix, a normalized name that happened to be nine
     * digits could resolve to the same vendor as an actual TIN.
     */
    public byte[] nameBlindIndex(long firmId, String normalizedName) {
        try {
            Mac mac = Mac.getInstance(MAC);
            mac.init(blindIndexKey);
            mac.update(Long.toString(firmId).getBytes(StandardCharsets.UTF_8));
            mac.update(":name:".getBytes(StandardCharsets.UTF_8));
            mac.update(normalizedName.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("name blind index computation failed", e);
        }
    }

    /**
     * Encrypts under the currently active key version.
     *
     * <p>Randomized: a fresh nonce per call, so the same TIN encrypted twice produces
     * different ciphertext. That is what keeps the ciphertext column from leaking
     * equality &mdash; equality lives in the blind index, deliberately and only there.
     *
     * @return ciphertext laid out as {@code nonce ‖ ciphertext ‖ tag}
     */
    public Encrypted encrypt(long firmId, long clientId, byte[] blindIndex, Tin tin) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(activeKeyVersion),
                        new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(firmId, clientId, blindIndex));

            byte[] sealed = cipher.doFinal(
                    tin.plaintextForTransmission().getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return new Encrypted(out, activeKeyVersion);
        } catch (GeneralSecurityException e) {
            // Deliberately does not include the plaintext, the nonce, or the key in the
            // message. An exception message is a log line waiting to happen.
            throw new IllegalStateException("TIN encryption failed for firm " + firmId, e);
        }
    }

    /**
     * Decrypts a stored TIN. One of exactly two legitimate call paths reaches this: building
     * an IRS submission payload, and an explicitly audited human "reveal" action.
     *
     * <p>A tag or AAD mismatch throws rather than returning a wrong value &mdash; which is
     * what makes the AAD binding useful: a ciphertext moved between vendor rows fails here
     * instead of quietly attributing one contractor's TIN to another.
     */
    public Tin decrypt(long firmId, long clientId, byte[] blindIndex, byte[] ciphertext, int keyVersion) {
        if (ciphertext == null || ciphertext.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("ciphertext is too short to contain a nonce");
        }

        ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] sealed = new byte[buffer.remaining()];
        buffer.get(sealed);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, keyFor(keyVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(firmId, clientId, blindIndex));

            String digits = new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
            Tin.Parsed parsed = Tin.parse(digits, null);
            if (!parsed.isPresent()) {
                throw new IllegalStateException("decrypted TIN is not nine digits; stored data is corrupt");
            }
            return parsed.tin();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "TIN decryption failed (key version " + keyVersion + "); ciphertext may belong to a "
                            + "different vendor row, or the key is wrong", e);
        }
    }

    private SecretKeySpec keyFor(int version) {
        SecretKeySpec key = encryptionKeys.get(version);
        if (key == null) {
            throw new IllegalStateException(
                    "no TIN encryption key configured for version " + version
                            + "; rotation must keep old versions available until the re-encrypt sweep completes");
        }
        return key;
    }

    /**
     * Binds the ciphertext to the row it belongs to. Any of these three changing makes the
     * ciphertext undecryptable, which is the intended behaviour.
     */
    private byte[] aad(long firmId, long clientId, byte[] blindIndex) {
        byte[] prefix = (firmId + ":" + clientId + ":").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + blindIndex.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(blindIndex, 0, out, prefix.length, blindIndex.length);
        return out;
    }

    /**
     * @param ciphertext {@code nonce ‖ ciphertext ‖ tag}
     * @param keyVersion stored alongside, so rotation never requires a coordinated cutover
     */
    public record Encrypted(byte[] ciphertext, int keyVersion) {
    }
}
