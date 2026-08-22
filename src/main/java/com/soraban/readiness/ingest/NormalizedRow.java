package com.soraban.readiness.ingest;

import java.time.LocalDate;

/**
 * One payment row after dialect parsing, normalization, and validation &mdash; ready to be
 * written into the COPY stream.
 *
 * <p>The shape matches {@code app.stg_ledger_line_template} column for column, because the
 * whole point of this type is to be rendered straight into a {@code COPY} record without
 * further transformation. Anything that needed deciding has already been decided by the
 * time a value lands here.
 *
 * <p>Note what has already happened upstream:
 * <ul>
 *   <li>The TIN has become a blind index plus ciphertext plus last-4. <b>No plaintext TIN
 *       reaches the database.</b></li>
 *   <li>The vendor name has been normalized, and the normalizer version recorded, so a
 *       later change to that algorithm is a visible migration rather than a silent
 *       reshuffle of who is whom.</li>
 *   <li>The amount is integer cents. The payment method is canonical, with the 1099-K
 *       exclusion flag already resolved.</li>
 *   <li>{@code rowHash} covers every determination-relevant field, which is what lets the
 *       merge tell "changed" from "identical" without comparing columns one by one.</li>
 * </ul>
 *
 * @param clientId      resolved from {@code client_ref} before parsing, so the encryption
 *                      AAD can bind to it
 * @param sourceTxnId   tier-1 native id, or a tier-2 {@code H:<hash>} synthesized for
 *                      spreadsheets that have no id column
 * @param fileLineNo    1-based, header included &mdash; cited by the rejection report
 */
public record NormalizedRow(
        String clientRef,
        long clientId,
        String sourceSystem,
        String sourceTxnId,
        String vendorNameRaw,
        String vendorNameNorm,
        short nameNormVersion,
        byte[] tinBidx,
        byte[] nameBidx,
        String tinStatus,
        String tinLast4,
        byte[] tinCiphertext,
        Integer tinKeyVersion,
        String tinRawMasked,
        LocalDate paymentDate,
        long amountCents,
        long withholdingCents,
        String methodCanon,
        boolean cardOrTpso,
        String entryType,
        String reversesSourceTxnId,
        String expenseClass,
        String currency,
        String memo,
        byte[] rowHash,
        long fileLineNo
) {
}
