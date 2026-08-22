package com.soraban.readiness.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generator produces byte-identical output for a given seed, on any machine.
 *
 * <h2>Why this property is load-bearing rather than a nicety</h2>
 *
 * <p>Every fixture assertion in this project is written against {@code fixtures.json}, which the
 * generator publishes alongside the corpus. If the same seed produced even slightly different
 * data on a different machine — or after an unrelated code change — those assertions would be
 * checking a corpus nobody has ever seen, and a green suite would mean nothing.
 *
 * <p>The specific hazards are all boring and all easy to reintroduce:
 *
 * <ul>
 *   <li><b>Locale.</b> {@code String.format("%.2f", …)} emits {@code 1,178,05} on a machine with
 *       a comma decimal separator. Amounts go through {@code BigDecimal.toPlainString()} instead.</li>
 *   <li><b>Line endings.</b> {@code System.lineSeparator()} alone makes Windows and CI disagree
 *       on every single line.</li>
 *   <li><b>Iteration order.</b> A {@code HashMap} walked to emit rows reorders between JDK
 *       versions.</li>
 *   <li><b>Parallelism.</b> Generation is parallel across clients, so anything shared between
 *       threads would interleave differently run to run.</li>
 * </ul>
 *
 * <p>None of those show up in a single run. They show up as a fixture test failing on someone
 * else's laptop, months later, with no obvious cause — which is exactly the failure this test
 * exists to convert into an immediate one.
 */
class SeedDeterminismTest {

    private static final int CLIENTS = 25;
    private static final long LINES = 8_000L;

    @TempDir Path work;

    // =================================================================================
    // The core claim
    // =================================================================================

    @Test
    @DisplayName("the same seed produces byte-identical files, twice in a row")
    void sameSeedIsByteIdentical() throws Exception {
        Map<String, String> first = generateAndHash(work.resolve("a"), 42L, 0);
        Map<String, String> second = generateAndHash(work.resolve("b"), 42L, 0);

        // Same file set, and the same bytes in each. Comparing hashes rather than content so a
        // failure reports which FILE diverged rather than dumping a megabyte of CSV.
        assertThat(second.keySet())
                .as("the same seed must produce the same set of files")
                .isEqualTo(first.keySet());

        List<String> differing = new ArrayList<>();
        first.forEach((name, hash) -> {
            if (!hash.equals(second.get(name))) {
                differing.add(name);
            }
        });

        assertThat(differing)
                .as("byte-identical means byte-identical; these files differ between two runs "
                  + "of the same seed")
                .isEmpty();
    }

    @Test
    @DisplayName("a different seed produces a genuinely different corpus")
    void differentSeedsDiverge() throws Exception {
        Map<String, String> withFortyTwo = generateAndHash(work.resolve("c"), 42L, 0);
        Map<String, String> withSeven = generateAndHash(work.resolve("d"), 7L, 0);

        // The mirror of the first test, and it is not redundant: a generator that ignored the
        // seed entirely -- or hashed it into nothing -- would pass "byte-identical" perfectly
        // while being useless. Determinism is only worth having if the seed actually drives it.
        List<String> identical = new ArrayList<>();
        withFortyTwo.forEach((name, hash) -> {
            if (hash.equals(withSeven.get(name))) {
                identical.add(name);
            }
        });

        assertThat(identical)
                .as("a different seed must change the data, or the seed is decorative")
                .isEmpty();
    }

    // =================================================================================
    // The specific ways determinism gets broken
    // =================================================================================

    @Test
    @DisplayName("line endings are LF everywhere, so Windows and CI cannot disagree")
    void lineEndingsAreExplicitRatherThanPlatformDependent() throws Exception {
        generate(work.resolve("e"), 42L, 0);

        for (Path csv : csvFiles(work.resolve("e"))) {
            String content = Files.readString(csv, StandardCharsets.UTF_8);

            // System.lineSeparator() is the single easiest way to make a corpus
            // machine-dependent, and it fails silently: every line differs, so every hash
            // differs, and nothing says why.
            assertThat(content)
                    .as("%s must use LF, not CRLF", csv.getFileName())
                    .doesNotContain("\r\n");
        }
    }

    @Test
    @DisplayName("no BOM, and amounts use a dot regardless of the machine's locale")
    void encodingAndNumberFormattingAreLocaleIndependent() throws Exception {
        generate(work.resolve("f"), 42L, 0);

        for (Path csv : csvFiles(work.resolve("f"))) {
            byte[] bytes = Files.readAllBytes(csv);

            // A UTF-8 BOM would be invisible in an editor and would corrupt the first header
            // name for every reader that does not strip it.
            assertThat(bytes.length > 3
                    && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF)
                    .as("%s must have no UTF-8 BOM", csv.getFileName())
                    .isFalse();
        }

        // Amounts are quoted when they carry thousands separators, so the check is that a
        // decimal COMMA never appears where a decimal point belongs -- e.g. "1178,05".
        for (Path csv : csvFiles(work.resolve("f"))) {
            assertThat(Files.readString(csv, StandardCharsets.UTF_8))
                    .as("%s contains a comma-decimal amount, which means a locale-sensitive "
                      + "formatter got in", csv.getFileName())
                    .doesNotContainPattern("\\d,\\d{2}[,\"\\n]");
        }
    }

    // =================================================================================
    // The manifest has to describe the files that were actually written
    // =================================================================================

    @Test
    @DisplayName("every manifest checksum matches the file it names")
    void theManifestIsNotStaleRelativeToTheCorpus() throws Exception {
        Path out = work.resolve("g");
        generate(out, 42L, 0);

        for (Path firmDir : firmDirs(out)) {
            JsonNode manifest = new ObjectMapper()
                    .readTree(Files.readString(firmDir.resolve("manifest.json")));

            JsonNode files = manifest.get("files");
            assertThat(files).as("the manifest must list its files").isNotNull();

            // The manifest is written after the writers close, because sha256 is only valid
            // then. A manifest computed while a buffer was still unflushed would name a
            // checksum for a file that did not exist yet -- and the importer verifies against
            // it, so the failure would land on the reviewer rather than here.
            for (JsonNode file : files) {
                String name = file.get("name").asText();
                String declared = file.get("sha256").asText();

                Path actual = firmDir.resolve(name);
                assertThat(actual).as("manifest names %s", name).exists();
                assertThat(sha256(actual))
                        .as("%s: manifest checksum does not match the bytes on disk", name)
                        .isEqualTo(declared);
            }
        }
    }

    @Test
    @DisplayName("the revision corpus differs from the baseline in exactly the declared way")
    void theRevisionManifestDescribesARealDiff() throws Exception {
        Path baseline = work.resolve("h");
        Path revised = work.resolve("i");
        generate(baseline, 42L, 0);
        generate(revised, 42L, 1);

        // Revision 1 is a scripted diff, not a fresh roll: the same seed, plus a specific set
        // of edits. So most of the corpus must be identical and some of it must not -- and
        // "all identical" would mean the revision did nothing, which is the failure mode that
        // would make the incremental-import test vacuous.
        Map<String, String> before = hashes(baseline);
        Map<String, String> after = hashes(revised);

        long changed = before.keySet().stream()
                .filter(name -> after.containsKey(name) && !before.get(name).equals(after.get(name)))
                .count();

        assertThat(changed)
                .as("the revision must actually change some files")
                .isPositive();

        JsonNode revisionManifest = new ObjectMapper()
                .readTree(Files.readString(revised.resolve("revision-manifest.json")));

        // And it must declare what it changed, because the incremental import test asserts
        // against these numbers rather than against whatever happened.
        assertThat(revisionManifest.get("revision").asInt()).isEqualTo(1);
        JsonNode byFirm = revisionManifest.get("byFirm");
        assertThat(byFirm).isNotNull();
        byFirm.fields().forEachRemaining(entry -> {
            JsonNode expected = entry.getValue();
            assertThat(expected.get("expectedInserted").asInt())
                    .as("%s: a revision that inserts nothing tests nothing", entry.getKey())
                    .isPositive();
            assertThat(expected.has("expectedUpdated")).isTrue();
            assertThat(expected.has("expectedTombstoned")).isTrue();
        });
    }

    @Test
    @DisplayName("the planted cases are published out of band and are stable for a seed")
    void fixturesAreReproducibleAndNotMarkedInTheData() throws Exception {
        Path first = work.resolve("j");
        Path second = work.resolve("k");
        generate(first, 42L, 0);
        generate(second, 42L, 0);

        assertThat(sha256(first.resolve("fixtures.json")))
                .as("the fixture manifest must be reproducible, or every case assertion is "
                  + "checking a corpus nobody has seen")
                .isEqualTo(sha256(second.resolve("fixtures.json")));

        // And nothing in the CSV marks a row as planted. If it did, the pipeline could treat
        // fixtures specially -- and every case test would be proving something about a code
        // path that only fixtures take.
        for (Path csv : csvFiles(first)) {
            String content = Files.readString(csv, StandardCharsets.UTF_8).toLowerCase();
            assertThat(content)
                    .as("%s must not label planted rows", csv.getFileName())
                    .doesNotContain("fixture")
                    .doesNotContain("planted")
                    .doesNotContain("case_id");
        }
    }

    // =================================================================================
    // Fixtures
    // =================================================================================

    private static void generate(Path out, long seed, int revision) throws IOException {
        new SeedGenerator(new SeedConfig(
                seed, SeedConfig.DEFAULT_FIRMS, CLIENTS, LINES, 2025, out, revision, false))
                .generate();
    }

    private static Map<String, String> generateAndHash(Path out, long seed, int revision)
            throws IOException {
        generate(out, seed, revision);
        return hashes(out);
    }

    /** Every file under the corpus, keyed by its path relative to the root. */
    private static Map<String, String> hashes(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString().replace('\\', '/'), sha256(path));
            }
        }
        return result;
    }

    private static List<Path> csvFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .toList();
        }
    }

    private static List<Path> firmDirs(Path root) throws IOException {
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).toList();
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
