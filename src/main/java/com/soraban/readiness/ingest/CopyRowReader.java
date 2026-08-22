package com.soraban.readiness.ingest;

import java.io.Reader;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Renders {@link NormalizedRow}s into the character stream that PostgreSQL's
 * {@code CopyManager} consumes, pulling one row at a time as the driver asks for bytes.
 *
 * <h2>Why a pull-based Reader rather than a PipedInputStream</h2>
 *
 * <p>The obvious way to feed {@code copyIn} is a producer thread writing into a
 * {@link java.io.PipedOutputStream}. It is also a well-known performance trap: the pipe's
 * internal buffer is 1 KB and every write is synchronized against the reader, which on this
 * exact pattern costs roughly an order of magnitude.
 *
 * <p>Implementing {@link Reader} directly inverts the control flow. The JDBC driver pulls,
 * this class renders exactly as much as was asked for, and the result is:
 * <ul>
 *   <li><b>no extra threads</b>, so no handoff and no synchronization;</li>
 *   <li><b>backpressure for free</b> &mdash; rows are rendered only when the driver is
 *       ready for them, so a slow network cannot cause unbounded buffering;</li>
 *   <li><b>nothing materialised</b> &mdash; a million rows never exist in memory at once.</li>
 * </ul>
 *
 * <h2>Format</h2>
 *
 * <p>{@code FORMAT csv}, not PostgreSQL's text format. Text format is measurably faster,
 * but its escaping rules ({@code \t}, {@code \n}, {@code \\}, {@code \N}) applied to
 * arbitrary vendor names and free-text memos are exactly where a subtle corruption hides
 * until February. Choosing the format whose escaping cannot be got wrong is worth a few
 * percent.
 *
 * <p>Two CSV conventions carry meaning here: an <b>unquoted empty field is NULL</b>, which
 * is how optional columns are expressed; and {@code bytea} values are written in
 * PostgreSQL's hex input form ({@code \x616263}), which passes through CSV untouched
 * because CSV does not treat backslash as an escape character.
 */
public class CopyRowReader extends Reader {

    /** Column order, and the exact list the COPY statement must name. */
    public static final String COPY_COLUMNS = """
            client_ref, source_system, source_txn_id, vendor_name_raw, vendor_name_norm, \
            name_norm_version, tin_bidx, name_bidx, tin_status, tin_last4, tin_ct, tin_key_ver, \
            tin_raw_masked, payment_date, amount_cents, withholding_cents, method_canon, \
            is_card_or_tpso, entry_type, reverses_source_txn_id, expense_class, currency, \
            memo, row_hash, file_line_no""";

    private static final HexFormat HEX = HexFormat.of();
    private static final int INITIAL_CAPACITY = 1 << 16;

    private final Iterator<NormalizedRow> source;
    private final StringBuilder buffer = new StringBuilder(INITIAL_CAPACITY);
    private int position;
    private long rowsRendered;

    public CopyRowReader(Iterator<NormalizedRow> source) {
        this.source = source;
    }

    @Override
    public int read(char[] target, int offset, int length) {
        if (length == 0) {
            return 0;
        }

        // Refill only when drained. Rendering whole rows at a time keeps the per-call cost
        // amortised without buffering ahead of what the driver has asked for.
        while (position >= buffer.length()) {
            if (!source.hasNext()) {
                return -1;
            }
            buffer.setLength(0);
            position = 0;
            // Render a batch so a small read() does not cost one row's rendering each time.
            for (int i = 0; i < 256 && source.hasNext(); i++) {
                append(source.next());
                rowsRendered++;
            }
        }

        int available = Math.min(length, buffer.length() - position);
        buffer.getChars(position, position + available, target, offset);
        position += available;
        return available;
    }

    private void append(NormalizedRow row) {
        text(row.clientRef());          comma();
        text(row.sourceSystem());       comma();
        text(row.sourceTxnId());        comma();
        text(row.vendorNameRaw());      comma();
        text(row.vendorNameNorm());     comma();
        buffer.append(row.nameNormVersion());  comma();
        bytea(row.tinBidx());           comma();
        bytea(row.nameBidx());          comma();
        text(row.tinStatus());          comma();
        text(row.tinLast4());           comma();
        bytea(row.tinCiphertext());     comma();
        nullableInt(row.tinKeyVersion()); comma();
        text(row.tinRawMasked());       comma();
        buffer.append(row.paymentDate());      comma();
        buffer.append(row.amountCents());      comma();
        buffer.append(row.withholdingCents()); comma();
        text(row.methodCanon());        comma();
        buffer.append(row.cardOrTpso() ? 't' : 'f'); comma();
        text(row.entryType());          comma();
        text(row.reversesSourceTxnId()); comma();
        text(row.expenseClass());       comma();
        text(row.currency());           comma();
        text(row.memo());               comma();
        bytea(row.rowHash());           comma();
        buffer.append(row.fileLineNo());
        buffer.append('\n');
    }

    private void comma() {
        buffer.append(',');
    }

    /**
     * Writes a text field, quoting only where RFC 4180 requires.
     *
     * <p>A null becomes an unquoted empty field, which COPY reads as SQL NULL. An
     * <em>empty string</em> would need to be written as {@code ""} to survive as a
     * zero-length value &mdash; the distinction matters, and every column that can legally
     * be empty here is one where NULL is the intended meaning.
     */
    private void text(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }

        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuoting = true;
                break;
            }
        }

        if (!needsQuoting) {
            buffer.append(value);
            return;
        }

        buffer.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                buffer.append('"');   // RFC 4180: a quote inside a quoted field is doubled
            }
            buffer.append(c);
        }
        buffer.append('"');
    }

    /** PostgreSQL hex input form. CSV leaves the backslash alone, so this arrives intact. */
    private void bytea(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        buffer.append("\\x").append(HEX.formatHex(value));
    }

    private void nullableInt(Integer value) {
        if (value != null) {
            buffer.append(value.intValue());
        }
    }

    public long rowsRendered() {
        return rowsRendered;
    }

    @Override
    public void close() {
        // Nothing to release: the underlying source is a plain iterator, and the row
        // supplier owns whatever file handle it reads from.
    }
}
