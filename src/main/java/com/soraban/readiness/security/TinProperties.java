package com.soraban.readiness.security;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Keys for TIN encryption and blind indexing.
 *
 * <h2>Two keys, deliberately</h2>
 *
 * <p>{@code keys} encrypts (AES-256-GCM); {@code blindIndexKey} derives the searchable
 * HMAC. They are separate so that compromising the index key &mdash; the one that must be
 * present for every {@code GROUP BY} and is therefore the more exposed of the two &mdash;
 * does not decrypt anything.
 *
 * <h2>Versioning without a rotation outage</h2>
 *
 * <p>{@code keys} is a map from version to key, and every ciphertext row stores the
 * version that produced it. Rotation is therefore a background re-encrypt keyed by
 * version rather than a big-bang migration: add version 2, flip
 * {@code activeKeyVersion}, and old rows keep decrypting under version 1 until a sweep
 * rewrites them. The sweep itself ({@code rotate-tin-key --from=1 --to=2}) is designed
 * here but deliberately not built &mdash; the write-up says so explicitly.
 *
 * <p>Dev defaults exist so the project runs immediately after clone. In any real
 * deployment both values come from the environment, and in production the keys would be
 * envelope-wrapped by a KMS-held KEK.
 *
 * @param activeKeyVersion which key new ciphertext is written under
 * @param keys             version to base64-encoded 32-byte AES key; must contain {@code activeKeyVersion}
 * @param blindIndexKey    base64-encoded 32-byte HMAC-SHA-256 key
 */
@Validated
@ConfigurationProperties("readiness.tin")
public record TinProperties(
        @NotNull Integer activeKeyVersion,
        @NotEmpty Map<Integer, String> keys,
        @NotEmpty String blindIndexKey
) {
    public TinProperties {
        if (activeKeyVersion != null && keys != null && !keys.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException(
                    "readiness.tin.active-key-version=" + activeKeyVersion
                            + " has no matching entry in readiness.tin.keys (have: " + keys.keySet() + ")");
        }
    }
}
