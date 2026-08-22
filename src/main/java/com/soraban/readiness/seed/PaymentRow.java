package com.soraban.readiness.seed;

import com.soraban.readiness.ledger.EntryType;
import com.soraban.readiness.ledger.ExpenseClass;
import com.soraban.readiness.ledger.PaymentMethod;
import com.soraban.readiness.support.Money;

import java.time.LocalDate;

/**
 * One generated payment row, held as the exact strings that will appear in the CSV.
 *
 * <h2>Why strings and not typed fields</h2>
 *
 * <p>The generator has to emit rows that are <em>deliberately invalid</em> &mdash; an
 * unparseable date, {@code "N/A"} in the amount column, a TIN of {@code "ABC-DEFGH"}, a
 * sub-cent value. Those cannot be represented by {@code LocalDate} or {@code long}, so a
 * typed model would force a parallel "broken row" type and two rendering paths, one of
 * which would inevitably drift from the other.
 *
 * <p>Holding CSV text throughout means the defect injector is simply "replace one field
 * with this other string", the writer has exactly one code path, and what the test asserts
 * about is literally what the importer will read.
 *
 * <p>{@link #of} builds a well-formed row from typed values, so ordinary generation stays
 * type-safe right up to the boundary.
 */
public record PaymentRow(
        String sourceSystem,
        String sourceTxnId,
        String clientRef,
        String vendorName,
        String vendorTin,
        String vendorTinType,
        String paymentDate,
        String amount,
        String currency,
        String paymentMethod,
        String entryType,
        String reversesSourceTxnId,
        String backupWithholding,
        String expenseCategory,
        String memo
) {

    /** Column order. Fixed, because byte-identical output depends on it. */
    public static final String[] HEADERS = {
            "source_system", "source_txn_id", "client_ref", "vendor_name", "vendor_tin",
            "vendor_tin_type", "payment_date", "amount", "currency", "payment_method",
            "entry_type", "reverses_source_txn_id", "backup_withholding", "expense_category", "memo"
    };

    /**
     * Builds a well-formed row from typed values.
     *
     * <p>Amounts render through {@link Money#toPlainString}, which goes via
     * {@code BigDecimal.toPlainString()} rather than {@code String.format}: format is
     * locale-sensitive, and on a machine with a comma decimal separator it would emit
     * {@code "825,00"} and produce a different corpus. That single detail is the
     * difference between a reproducible seed and one that only reproduces on the machine
     * it was written on.
     */
    public static PaymentRow of(String sourceSystem,
                                String sourceTxnId,
                                String clientRef,
                                String vendorName,
                                String vendorTin,
                                String vendorTinType,
                                LocalDate paymentDate,
                                long amountCents,
                                PaymentMethod paymentMethod,
                                EntryType entryType,
                                String reversesSourceTxnId,
                                long backupWithholdingCents,
                                ExpenseClass expenseClass,
                                String memo) {
        return new PaymentRow(
                sourceSystem,
                sourceTxnId,
                clientRef,
                vendorName,
                vendorTin == null ? "" : vendorTin,
                vendorTinType == null ? "" : vendorTinType,
                paymentDate.toString(),                       // ISO-8601, locale-independent
                Money.toPlainString(amountCents),
                "USD",
                paymentMethod.displayName(),
                entryType.name(),
                reversesSourceTxnId == null ? "" : reversesSourceTxnId,
                backupWithholdingCents == 0 ? "" : Money.toPlainString(backupWithholdingCents),
                expenseClass.name(),
                memo == null ? "" : memo
        );
    }

    /** The fields in {@link #HEADERS} order, for the writer. */
    public String[] toArray() {
        return new String[]{
                sourceSystem, sourceTxnId, clientRef, vendorName, vendorTin, vendorTinType,
                paymentDate, amount, currency, paymentMethod, entryType, reversesSourceTxnId,
                backupWithholding, expenseCategory, memo
        };
    }

    /**
     * Re-renders the date and amount columns in a specific source system's format.
     *
     * <p>Rows are built in canonical form (ISO dates, plain decimals) and converted at
     * write time, so the generator has one construction path and the dialect owns all
     * formatting.
     *
     * <p>This is what makes the three export files genuinely different rather than
     * identical CSV under three filenames: QuickBooks gets {@code 03/04/2025}, Xero gets
     * {@code 2025-03-04}, and a hand-maintained spreadsheet gets {@code 4-Mar-25} with a
     * dollar sign, thousands separators, and accounting parentheses for negatives. Without
     * this, the importer's dialect layer would only ever be tested against data it already
     * matched.
     *
     * @param renderDate   converts an ISO date string into the dialect's format
     * @param renderAmount converts a plain decimal string into the dialect's format
     */
    public PaymentRow renderedFor(java.util.function.UnaryOperator<String> renderDate,
                                  java.util.function.UnaryOperator<String> renderAmount) {
        return new PaymentRow(
                sourceSystem, sourceTxnId, clientRef, vendorName, vendorTin, vendorTinType,
                renderDate.apply(paymentDate),
                renderAmount.apply(amount),
                currency, paymentMethod, entryType, reversesSourceTxnId,
                backupWithholding.isEmpty() ? "" : renderAmount.apply(backupWithholding),
                expenseCategory, memo);
    }

    /** Returns a copy with one column replaced. Used by the defect injector. */
    public PaymentRow with(int columnIndex, String value) {
        String[] fields = toArray();
        fields[columnIndex] = value;
        return new PaymentRow(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
                              fields[6], fields[7], fields[8], fields[9], fields[10], fields[11],
                              fields[12], fields[13], fields[14]);
    }

    // Column indices, for the defect injector.
    public static final int COL_SOURCE_SYSTEM = 0;
    public static final int COL_SOURCE_TXN_ID = 1;
    public static final int COL_CLIENT_REF = 2;
    public static final int COL_VENDOR_NAME = 3;
    public static final int COL_VENDOR_TIN = 4;
    public static final int COL_VENDOR_TIN_TYPE = 5;
    public static final int COL_PAYMENT_DATE = 6;
    public static final int COL_AMOUNT = 7;
    public static final int COL_CURRENCY = 8;
    public static final int COL_PAYMENT_METHOD = 9;
    public static final int COL_ENTRY_TYPE = 10;
    public static final int COL_REVERSES = 11;
    public static final int COL_BACKUP_WITHHOLDING = 12;
    public static final int COL_EXPENSE_CATEGORY = 13;
    public static final int COL_MEMO = 14;
}
