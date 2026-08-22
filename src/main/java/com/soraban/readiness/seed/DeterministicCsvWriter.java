package com.soraban.readiness.seed;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

/**
 * Writes CSV whose bytes are identical for a given seed on any machine, any JDK, forever.
 *
 * <h2>Everything that has to be pinned</h2>
 *
 * <p>The PRNG is the obvious half of determinism and the easy half. These are the details
 * that actually break it in practice, each pinned deliberately:
 *
 * <ul>
 *   <li><b>{@code '\n'}, never {@link System#lineSeparator()}.</b> On its own this would
 *       make every file differ between a Windows dev machine and a Linux CI box &mdash; the
 *       single most likely way a "deterministic" generator turns out not to be.</li>
 *   <li><b>UTF-8 with no BOM.</b> Some tools add a byte-order mark; three bytes at the head
 *       of the file would change every checksum.</li>
 *   <li><b>No {@code String.format}, no {@code NumberFormat}, no {@code DecimalFormat}.</b>
 *       All are locale-sensitive: on a machine with a comma decimal separator they emit
 *       {@code "825,00"}. Amounts and dates are rendered by hand or through
 *       {@code Locale.ROOT} formatters.</li>
 *   <li><b>Fixed column order</b>, from {@link PaymentRow#HEADERS}.</li>
 *   <li><b>Deterministic quoting.</b> A field is quoted under exactly one rule (below), so
 *       two runs cannot disagree about whether a given value needed quotes.</li>
 * </ul>
 *
 * <p>The class computes the file's SHA-256 as it writes, because the manifest needs a
 * checksum per file and re-reading a 70 MB file to hash it afterwards is pure waste.
 *
 * <h2>Format choice</h2>
 *
 * <p>Emits RFC 4180 CSV rather than Postgres text format. Text format is measurably faster
 * to load, but its escaping rules applied to arbitrary vendor names and free-text memos are
 * exactly where a subtle corruption hides until February. Choosing the format whose
 * escaping cannot be got wrong is worth a few percent.
 */
public final class DeterministicCsvWriter implements AutoCloseable {

    /**
     * 1 MiB. Generation is I/O-bound rather than compute-bound, so buffer size is the main
     * lever; the default 8 KiB costs roughly an order of magnitude more syscalls across a
     * 140 MB corpus.
     */
    private static final int BUFFER_CHARS = 1 << 20;

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';
    private static final char LF = '\n';

    private final Writer writer;
    private final MessageDigest digest;
    private final Path path;
    private long rowsWritten;

    private DeterministicCsvWriter(Path path, Writer writer, MessageDigest digest) {
        this.path = path;
        this.writer = writer;
        this.digest = digest;
    }

    /**
     * Opens a writer for {@code path}, gzipping when the filename ends in {@code .gz}.
     *
     * <p>The digest is taken over the bytes actually written to disk, so for a gzipped file
     * it covers the compressed stream. That is the right choice: the manifest checksum
     * exists to verify the artifact on disk is intact, and the importer verifies it before
     * decompressing.
     */
    public static DeterministicCsvWriter open(Path path) throws IOException {
        Files.createDirectories(path.getParent());

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and always present", e);
        }

        OutputStream out = Files.newOutputStream(path);
        out = new DigestOutputStream(out, digest);
        if (path.getFileName().toString().endsWith(".gz")) {
            out = new GZIPOutputStream(out, 1 << 16);
        }

        Writer writer = new BufferedWriter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), BUFFER_CHARS);

        return new DeterministicCsvWriter(path, writer, digest);
    }

    /** Writes the header row. */
    public void writeHeader(String[] headers) throws IOException {
        writeRow(headers);
    }

    public void writeRow(PaymentRow row) throws IOException {
        writeRow(row.toArray());
    }

    /** Writes one record, quoting each field only where RFC 4180 requires it. */
    public void writeRow(String[] fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                writer.write(DELIMITER);
            }
            writeField(fields[i]);
        }
        writer.write(LF);
        rowsWritten++;
    }

    /**
     * Quotes when the value contains a delimiter, a quote, a line break, or has leading or
     * trailing whitespace that would otherwise be silently trimmed by a lenient parser.
     *
     * <p>Written as one explicit rule rather than "quote everything" so the generated files
     * stay readable in a text editor &mdash; a reviewer will open one, and a file where
     * every field is quoted is materially harder to scan.
     */
    private void writeField(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }

        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == DELIMITER || c == QUOTE || c == '\n' || c == '\r') {
                needsQuoting = true;
                break;
            }
        }
        if (!needsQuoting) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            needsQuoting = first == ' ' || first == '\t' || last == ' ' || last == '\t';
        }

        if (!needsQuoting) {
            writer.write(value);
            return;
        }

        writer.write(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == QUOTE) {
                writer.write(QUOTE);   // RFC 4180: a quote inside a quoted field is doubled
            }
            writer.write(c);
        }
        writer.write(QUOTE);
    }

    /**
     * Writes a pre-rendered line verbatim, including the newline.
     *
     * <p>Exists solely for the {@code RAGGED_ROW} defect class, which by definition cannot
     * be expressed as a well-formed record &mdash; a row with the wrong column count or an
     * unescaped embedded quote. The importer has to survive those, so the generator has to
     * be able to emit them.
     */
    public void writeRawLine(String line) throws IOException {
        writer.write(line);
        writer.write(LF);
        rowsWritten++;
    }

    public long rowsWritten() {
        return rowsWritten;
    }

    public Path path() {
        return path;
    }

    /**
     * The SHA-256 of everything written. Valid only after {@link #close()}, since buffered
     * bytes have not reached the digest before then.
     */
    public String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
