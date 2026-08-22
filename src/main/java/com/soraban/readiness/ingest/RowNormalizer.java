package com.soraban.readiness.ingest;

import com.soraban.readiness.ledger.EntryType;
import com.soraban.readiness.ledger.ExpenseClass;
import com.soraban.readiness.ledger.PaymentMethod;
import com.soraban.readiness.ledger.VendorNameNormalizer;
import com.soraban.readiness.security.Tin;
import com.soraban.readiness.security.TinCryptoService;
import com.soraban.readiness.support.Money;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns one raw CSV record into either a {@link NormalizedRow} or a rejection.
 *
 * <h2>This runs BEFORE the COPY stream, and that is the whole design</h2>
 *
 * <p>{@code COPY} is all-or-nothing per stream: one malformed record aborts the entire
 * transfer. So a bad row must never reach it. Filtering here &mdash; in Java, ahead of the
 * stream &mdash; is what makes <em>"a malformed row is skipped and reported, never a reason
 * the whole import dies"</em> a structural property rather than a {@code try/catch} someone
 * has to remember to write.
 *
 * <p>It also means one pass over the record does dialect parsing, normalization, TIN
 * handling, the row hash, and tier-2 key synthesis together, rather than reading the data
 * four times.
 *
 * <h2>Stateful, single-threaded, one instance per file</h2>
 *
 * <p>Two caches make this worth it: the duplicate-ordinal counter (needed for tier-2
 * identity) and the TIN encryption cache. The latter matters &mdash; encrypting per row
 * would mean a million AES operations, where caching by vendor reduces it to roughly the
 * number of distinct vendors, a fiftyfold saving on an operation we do not need to repeat.
 */
public class RowNormalizer {

    /** Column names this normalizer reads, after dialect aliasing. */
    private static final String C_CLIENT_REF = "client_ref";
    private static final String C_SOURCE_SYSTEM = "source_system";
    private static final String C_SOURCE_TXN_ID = "source_txn_id";
    private static final String C_VENDOR_NAME = "vendor_name";
    private static final String C_VENDOR_TIN = "vendor_tin";
    private static final String C_VENDOR_TIN_TYPE = "vendor_tin_type";
    private static final String C_PAYMENT_DATE = "payment_date";
    private static final String C_AMOUNT = "amount";
    private static final String C_CURRENCY = "currency";
    private static final String C_PAYMENT_METHOD = "payment_method";
    private static final String C_ENTRY_TYPE = "entry_type";
    private static final String C_REVERSES = "reverses_source_txn_id";
    private static final String C_BACKUP_WITHHOLDING = "backup_withholding";
    private static final String C_EXPENSE_CATEGORY = "expense_category";
    private static final String C_MEMO = "memo";

    private final long firmId;
    private final SourceDialect dialect;
    private final String declaredSourceSystem;
    private final Map<String, Long> clientIdsByRef;
    private final TinCryptoService tinCrypto;
    private final Map<String, Integer> columnIndex;

    /**
     * Counts how many times a given natural tuple has already appeared in this file.
     *
     * <p>Feeds the tier-2 duplicate ordinal. Two identical $50 cheques to the same plumber
     * on the same day genuinely happen; without the ordinal, a pure content hash would
     * collapse them into one row and under-report the vendor by $50.
     */
    private final Map<String, Integer> duplicateOrdinals = new HashMap<>();

    /** Encrypted TIN per (client, digits). Randomized GCM, so one ciphertext per vendor. */
    private final Map<String, TinCryptoService.Encrypted> encryptionCache = new HashMap<>();

    private final MessageDigest sha256;

    public RowNormalizer(long firmId,
                         SourceDialect dialect,
                         String declaredSourceSystem,
                         Map<String, Long> clientIdsByRef,
                         TinCryptoService tinCrypto,
                         Map<String, Integer> columnIndex) {
        this.firmId = firmId;
        this.dialect = dialect;
        this.declaredSourceSystem = declaredSourceSystem;
        this.clientIdsByRef = clientIdsByRef;
        this.tinCrypto = tinCrypto;
        this.columnIndex = columnIndex;
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and always present", e);
        }
    }

    /**
     * Outcome of normalizing one record. Exactly one of {@code row} / {@code rejection} is set.
     *
     * @param detail extra context for the rejection report, e.g. the offending text
     */
    public record Result(NormalizedRow row, RejectionCode rejection, String detail) {

        static Result ok(NormalizedRow row) {
            return new Result(row, null, null);
        }

        static Result reject(RejectionCode code, String detail) {
            return new Result(null, code, detail);
        }

        public boolean isRejected() {
            return rejection != null;
        }
    }

    /**
     * @param fields     the parsed record
     * @param fileLineNo 1-based line number, header included
     */
    public Result normalize(String[] fields, long fileLineNo) {
        // Column count is the first gate. A record with the wrong shape cannot be read
        // field by field without silently taking values from the wrong columns -- which
        // would be far worse than rejecting it, because it would import successfully and
        // be wrong.
        if (fields.length != columnIndex.size()) {
            return Result.reject(RejectionCode.RAGGED_ROW,
                    "expected " + columnIndex.size() + " columns, found " + fields.length);
        }

        String clientRef = value(fields, C_CLIENT_REF);
        if (clientRef == null || clientRef.isBlank()) {
            return Result.reject(RejectionCode.MISSING_CLIENT_REF, null);
        }
        Long clientId = clientIdsByRef.get(clientRef.strip());
        if (clientId == null) {
            return Result.reject(RejectionCode.UNKNOWN_CLIENT_REF, clientRef.strip());
        }

        String currency = value(fields, C_CURRENCY);
        if (currency != null && !currency.isBlank() && !"USD".equalsIgnoreCase(currency.strip())) {
            return Result.reject(RejectionCode.UNSUPPORTED_CURRENCY, currency.strip());
        }

        String rawDate = value(fields, C_PAYMENT_DATE);
        LocalDate paymentDate = dialect.parseDate(rawDate);
        if (paymentDate == null) {
            return Result.reject(RejectionCode.UNPARSEABLE_DATE, rawDate);
        }

        String rawAmount = value(fields, C_AMOUNT);
        BigDecimal amount = dialect.parseAmount(rawAmount);
        if (amount == null) {
            return Result.reject(RejectionCode.UNPARSEABLE_AMOUNT, rawAmount);
        }
        long amountCents;
        try {
            amountCents = Money.toCents(amount);
        } catch (ArithmeticException e) {
            // Distinguished from UNPARSEABLE_AMOUNT on purpose: "12.345" is a different
            // problem from "N/A", and a human fixes them differently.
            return Result.reject(RejectionCode.SUB_CENT_AMOUNT, rawAmount);
        }

        String vendorNameRaw = orEmpty(value(fields, C_VENDOR_NAME)).strip();
        String rawTin = value(fields, C_VENDOR_TIN);
        Tin.Parsed parsedTin = Tin.parse(rawTin, value(fields, C_VENDOR_TIN_TYPE));

        // Name AND TIN both absent: nothing to hang an obligation on. A blank TIN alone is
        // ordinary data and flows through normally.
        if (vendorNameRaw.isEmpty() && !parsedTin.isPresent()) {
            return Result.reject(RejectionCode.UNIDENTIFIABLE_VENDOR, null);
        }

        String vendorNameNorm = VendorNameNormalizer.normalize(vendorNameRaw);

        byte[] tinBidx = null;
        byte[] tinCiphertext = null;
        Integer tinKeyVersion = null;
        String tinLast4 = null;
        String tinRawMasked = null;

        if (parsedTin.isPresent()) {
            Tin tin = parsedTin.tin();
            tinBidx = tinCrypto.blindIndex(firmId, tin);
            tinLast4 = tin.last4();

            TinCryptoService.Encrypted encrypted = encryptionCache.computeIfAbsent(
                    clientId + ":" + tin.plaintextForTransmission(),
                    key -> tinCrypto.encrypt(firmId, clientId, tinCrypto.blindIndex(firmId, tin), tin));
            tinCiphertext = encrypted.ciphertext();
            tinKeyVersion = encrypted.keyVersion();
        } else if (parsedTin.status() == Tin.Status.MALFORMED) {
            // Preserved for a human, masked so the report never carries a full identifier,
            // and deliberately NOT used as an identity key: we will not make an
            // unverifiable string load-bearing for who a vendor is.
            tinRawMasked = maskTin(parsedTin.rawIfMalformed());
        }

        long withholdingCents = 0;
        BigDecimal withholding = dialect.parseAmount(value(fields, C_BACKUP_WITHHOLDING));
        if (withholding != null) {
            try {
                withholdingCents = Math.abs(Money.toCents(withholding));
            } catch (ArithmeticException e) {
                withholdingCents = 0;   // a sub-cent withholding is noise, not a reason to lose the payment
            }
        }

        PaymentMethod method = PaymentMethod.fromRaw(value(fields, C_PAYMENT_METHOD));
        EntryType entryType = EntryType.fromRaw(value(fields, C_ENTRY_TYPE), amountCents);
        ExpenseClass expenseClass = ExpenseClass.fromRaw(value(fields, C_EXPENSE_CATEGORY));

        String sourceSystem = orDefault(value(fields, C_SOURCE_SYSTEM), declaredSourceSystem);
        String memo = orEmpty(value(fields, C_MEMO));
        String reverses = orEmpty(value(fields, C_REVERSES)).strip();

        String sourceTxnId = resolveSourceTxnId(
                value(fields, C_SOURCE_TXN_ID), clientRef, vendorNameNorm, tinBidx,
                paymentDate, amountCents, method, entryType, withholdingCents, expenseClass);

        byte[] rowHash = computeRowHash(
                clientRef, sourceSystem, sourceTxnId, vendorNameRaw, vendorNameNorm, tinBidx,
                parsedTin.status().name(), paymentDate, amountCents, withholdingCents,
                method, entryType, reverses, expenseClass, memo);

        // Always computed, even when a TIN is present: determination's promotion rule needs
        // to group no-TIN rows by name within a client, and the vendor upsert keys on
        // coalesce(tin_bidx, name_bidx). The ':name:' domain prefix inside the HMAC means a
        // name-derived key can never collide with a TIN-derived one.
        byte[] nameBidx = tinCrypto.nameBlindIndex(firmId, vendorNameNorm);

        return Result.ok(new NormalizedRow(
                clientRef.strip(), clientId, sourceSystem, sourceTxnId,
                vendorNameRaw, vendorNameNorm, VendorNameNormalizer.VERSION,
                tinBidx, nameBidx, parsedTin.status().name(), tinLast4, tinCiphertext, tinKeyVersion, tinRawMasked,
                paymentDate, amountCents, withholdingCents,
                method.name(), method.isCardOrTpso(), entryType.name(),
                reverses.isEmpty() ? null : reverses,
                expenseClass.name(), "USD", memo, rowHash, fileLineNo));
    }

    /**
     * Tier-1 native id when the export supplies one; tier-2 synthesized otherwise.
     *
     * <p>QuickBooks and Xero both carry a stable payment id, which is the ideal identity:
     * it survives the bookkeeper editing the row's <em>contents</em>, so an amount
     * correction in a revised export reads as an UPDATE to the same row rather than a
     * delete plus an insert.
     *
     * <p>Spreadsheets have no such column, so identity is synthesized from the natural
     * tuple plus a duplicate ordinal. The known limitation, owned rather than hidden:
     * reordering two byte-identical spreadsheet rows swaps their synthesized ids and
     * produces a spurious delete/insert pair. That is provably determination-neutral --
     * the natural tuple covers every field determination reads, so the vendor's totals and
     * dispositions are identical either way. The cost is churn in surrogate ids and one
     * extra dirty mark, which is the right place to spend the imprecision.
     */
    private String resolveSourceTxnId(String nativeId, String clientRef, String vendorNameNorm,
                                      byte[] tinBidx, LocalDate paymentDate, long amountCents,
                                      PaymentMethod method, EntryType entryType,
                                      long withholdingCents, ExpenseClass expenseClass) {
        if (nativeId != null && !nativeId.isBlank()) {
            return nativeId.strip();
        }

        String naturalTuple = String.join("",
                clientRef.strip(), vendorNameNorm,
                tinBidx == null ? "" : hex(tinBidx),
                paymentDate.toString(), Long.toString(amountCents),
                method.name(), entryType.name(),
                Long.toString(withholdingCents), expenseClass.name());

        int ordinal = duplicateOrdinals.merge(naturalTuple, 1, Integer::sum);

        sha256.reset();
        sha256.update(naturalTuple.getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) ':');
        sha256.update(Integer.toString(ordinal).getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha256.digest();

        // 16 hex chars is 64 bits. Within one client's spreadsheet -- tens of thousands of
        // rows at most -- collision probability is negligible, and the shorter key keeps
        // the unique index compact on a million-row table.
        return "H:" + hex(digest).substring(0, 16);
    }

    /**
     * Covers every field determination reads, and nothing else.
     *
     * <p>Excluding {@code memo} and {@code file_line_no} is deliberate: a bookkeeper
     * retyping a memo, or the same row moving to a different line of a re-exported file,
     * must not count as a change. If it did, a cosmetic edit would mark the client dirty
     * and trigger a pointless re-determination &mdash; and "importing the same file twice
     * changes nothing" would quietly stop being true.
     */
    private byte[] computeRowHash(String clientRef, String sourceSystem, String sourceTxnId,
                                  String vendorNameRaw, String vendorNameNorm, byte[] tinBidx,
                                  String tinStatus, LocalDate paymentDate, long amountCents,
                                  long withholdingCents, PaymentMethod method, EntryType entryType,
                                  String reverses, ExpenseClass expenseClass, String memo) {
        sha256.reset();
        update(clientRef);
        update(sourceSystem);
        update(sourceTxnId);
        update(vendorNameRaw);
        update(vendorNameNorm);
        sha256.update(tinBidx == null ? new byte[0] : tinBidx);
        update(tinStatus);
        update(paymentDate.toString());
        update(Long.toString(amountCents));
        update(Long.toString(withholdingCents));
        update(method.name());
        update(Boolean.toString(method.isCardOrTpso()));
        update(entryType.name());
        update(reverses);
        update(expenseClass.name());
        return sha256.digest();
    }

    private void update(String value) {
        sha256.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) 0x1f);   // field separator, so "ab"+"c" cannot hash as "a"+"bc"
    }

    private String value(String[] fields, String canonicalColumn) {
        Integer index = columnIndex.get(canonicalColumn);
        if (index == null || index >= fields.length) {
            return null;
        }
        return fields[index];
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** Keeps the last four digits so a human can recognise the vendor; hides the rest. */
    private static String maskTin(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() >= 4) {
            return "***-**-" + digits.substring(digits.length() - 4);
        }
        return "***";
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
