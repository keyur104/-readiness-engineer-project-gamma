package com.soraban.readiness.seed;

import java.util.List;

/**
 * Corrupts a well-formed row into a specific, labelled defect.
 *
 * <p>Every method here produces a row whose expected fate is already known and recorded in
 * {@code fixtures.json}, so the test suite asserts the <b>rejection report</b> rather than
 * merely checking that the import survived. An importer that skipped every bad row while
 * reporting the wrong reason would pass a "did it finish?" test and fail these.
 *
 * <h2>Two shapes of corruption</h2>
 *
 * <p>Most defects are field-level and stay representable as a {@link PaymentRow} &mdash;
 * an unparseable date is still fifteen comma-separated fields. Two are not:
 * {@code RAGGED_ROW} has the wrong column count or an unescaped quote, so it can only be
 * emitted as a raw line. {@link Injected} carries whichever form applies.
 */
final class DefectInjector {

    /**
     * The result of injecting one defect. Exactly one of the two fields is non-null.
     *
     * @param row      the corrupted row, when the defect is still a well-formed record
     * @param rawLine  verbatim text, when the defect breaks CSV structure itself
     */
    record Injected(PaymentRow row, String rawLine) {

        static Injected ofRow(PaymentRow row) {
            return new Injected(row, null);
        }

        static Injected ofRaw(String line) {
            return new Injected(null, line);
        }

        boolean isRaw() {
            return rawLine != null;
        }
    }

    /** Defects the injector can apply to an arbitrary row, weighted by how common they are. */
    static final List<DefectClass> INJECTABLE = List.of(
            DefectClass.UNPARSEABLE_DATE,
            DefectClass.UNPARSEABLE_AMOUNT,
            DefectClass.SUB_CENT_AMOUNT,
            DefectClass.MISSING_CLIENT_REF,
            DefectClass.UNKNOWN_CLIENT_REF,
            DefectClass.UNIDENTIFIABLE_VENDOR,
            DefectClass.RAGGED_ROW,
            DefectClass.UNSUPPORTED_CURRENCY,
            DefectClass.MALFORMED_TIN,
            DefectClass.UNKNOWN_PAYMENT_METHOD,
            DefectClass.ZERO_AMOUNT
    );

    private static final String[] BAD_DATES = {
            "31/02/2025",       // a day that does not exist
            "not a date",
            "2025-13-45",       // month 13, day 45
            "02/30/2025",       // February 30th
            "",                 // blank in a required column
            "TBD"
    };

    private static final String[] BAD_AMOUNTS = {
            "N/A",
            "",
            "1,2,3.4",          // separators in the wrong places
            "twelve dollars",
            "--500.00",
            "$$100"
    };

    private static final String[] BAD_TINS = {
            "12-34567",         // too few digits
            "ABC-DEFGH",        // not digits at all
            "123-45-678",       // one short
            "000-00-0000",      // structurally nine digits but meaningless
            "N/A",
            "pending W-9"       // the bookkeeper's note, typed into the TIN column
    };

    private static final String[] UNKNOWN_METHODS = {
            "barter", "trade credit", "offset", "misc", "see notes", "zelle"
    };

    private DefectInjector() {
    }

    /**
     * Applies {@code defect} to {@code row}.
     *
     * @param unknownClientRef a client reference guaranteed absent from {@code clients.csv},
     *                         supplied by the caller so the generator controls what "unknown"
     *                         means rather than inventing a value that might collide
     */
    static Injected inject(DefectClass defect, PaymentRow row, Xoshiro256StarStar rng,
                           String unknownClientRef) {
        return switch (defect) {
            case UNPARSEABLE_DATE ->
                    Injected.ofRow(row.with(PaymentRow.COL_PAYMENT_DATE, rng.pick(BAD_DATES)));

            case UNPARSEABLE_AMOUNT ->
                    Injected.ofRow(row.with(PaymentRow.COL_AMOUNT, rng.pick(BAD_AMOUNTS)));

            // Three decimal places. Rejected rather than rounded: silently discarding a
            // fraction of a cent makes the imported book disagree with the source by an
            // amount nobody can later account for.
            case SUB_CENT_AMOUNT ->
                    Injected.ofRow(row.with(PaymentRow.COL_AMOUNT, "12.345"));

            case MISSING_CLIENT_REF ->
                    Injected.ofRow(row.with(PaymentRow.COL_CLIENT_REF, ""));

            case UNKNOWN_CLIENT_REF ->
                    Injected.ofRow(row.with(PaymentRow.COL_CLIENT_REF, unknownClientRef));

            // Name AND TIN both blank: the one case where a vendor genuinely cannot be
            // identified even as an exception, so there is nothing to attach an obligation
            // to. Contrast with a blank TIN alone, which is ordinary data.
            case UNIDENTIFIABLE_VENDOR ->
                    Injected.ofRow(row.with(PaymentRow.COL_VENDOR_NAME, "")
                                      .with(PaymentRow.COL_VENDOR_TIN, ""));

            case UNSUPPORTED_CURRENCY ->
                    Injected.ofRow(row.with(PaymentRow.COL_CURRENCY, rng.nextBoolean(0.5) ? "CAD" : "EUR"));

            // Imported successfully; becomes a determination exception. Preserved for a
            // human, never used as an identity key.
            case MALFORMED_TIN ->
                    Injected.ofRow(row.with(PaymentRow.COL_VENDOR_TIN, rng.pick(BAD_TINS)));

            // Also imported. Counts toward the threshold (the conservative direction) and
            // is flagged, because guessing "card" would suppress a required filing.
            case UNKNOWN_PAYMENT_METHOD ->
                    Injected.ofRow(row.with(PaymentRow.COL_PAYMENT_METHOD, rng.pick(UNKNOWN_METHODS)));

            case ZERO_AMOUNT ->
                    Injected.ofRow(row.with(PaymentRow.COL_AMOUNT, "0.00"));

            case RAGGED_ROW ->
                    Injected.ofRaw(ragged(row, rng));

            // Not corruption: the same row emitted twice. The caller handles this by writing
            // the row a second time, since it is about file structure rather than content.
            case MISSING_TIN ->
                    Injected.ofRow(row.with(PaymentRow.COL_VENDOR_TIN, "")
                                      .with(PaymentRow.COL_VENDOR_TIN_TYPE, ""));

            case OUT_OF_TAX_YEAR, DUPLICATE_KEY_IN_FILE ->
                    Injected.ofRow(row);   // handled by the generator, not by field corruption
        };
    }

    /**
     * Breaks CSV structure itself, in one of three ways real files break.
     *
     * <p>These matter because they are what a naive line-splitting parser gets wrong, and
     * because the importer must skip them without the whole file dying. An unescaped quote
     * in particular can swallow every subsequent line if the parser is not careful &mdash;
     * one bad row becoming a thousand.
     */
    private static String ragged(PaymentRow row, Xoshiro256StarStar rng) {
        String[] fields = row.toArray();

        return switch (rng.nextInt(3)) {
            // Too few columns: a truncated export, or a hand-edited file where someone
            // deleted a cell instead of clearing it.
            case 0 -> String.join(",", java.util.Arrays.copyOfRange(fields, 0, fields.length - 4));

            // Too many columns: a stray trailing comma, or a merged sheet with an extra column.
            case 1 -> String.join(",", fields) + ",unexpected,extra";

            // An unescaped quote inside a field. The classic one: it is only wrong because
            // the quote was not doubled, and a parser that does not track quote state will
            // consume following lines into this record.
            default -> {
                String[] copy = fields.clone();
                copy[PaymentRow.COL_VENDOR_NAME] = "Bob \"Big Bob\" Henderson & Sons";
                copy[PaymentRow.COL_MEMO] = "note: \"rush job\", see file";
                yield String.join(",", copy);
            }
        };
    }
}
