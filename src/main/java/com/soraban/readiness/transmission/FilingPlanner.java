package com.soraban.readiness.transmission;

import com.soraban.readiness.security.FirmContext;
import com.soraban.readiness.security.Tin;
import com.soraban.readiness.security.TinCryptoService;
import com.soraban.readiness.transmission.domain.AttentionType;
import com.soraban.readiness.transmission.domain.FilingState;
import com.soraban.readiness.transmission.domain.IdempotencyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns determination results into filings.
 *
 * <p>The seam between "what is owed" and "what we will transmit". Two things happen here and
 * both matter:
 *
 * <h2>1. Filing identity is derived, not generated</h2>
 *
 * <p>Every filing's id is {@code uuidv5(firm, client, taxYear, vendorKey)}, so re-running
 * determination &mdash; after a revised export, or because an operator re-ran a task --
 * <b>converges on the same row</b> rather than minting a new one. Without that, a re-run
 * would produce fresh ids, fresh idempotency keys, and a resubmission of filings already
 * live at the IRS. Transmission idempotency is only ever as strong as determination
 * idempotency.
 *
 * <h2>2. Preflight decides transmittable, never whether a form is owed</h2>
 *
 * <p>A vendor with no TIN still gets a filing row. It lands in {@link FilingState#BLOCKED}
 * with an attention item, is counted, is visible, and is assignable to a person &mdash; it
 * is emphatically not skipped. The brief is explicit that a missing TIN must never be a
 * reason to silently drop a vendor, and the way to guarantee that is to make the filing
 * exist regardless and block only the transmission.
 *
 * <p>Preflight also rejects what the IRS provably would: a TIN beginning {@code 000}, a
 * non-positive amount. Catching those here means they never consume one of the twenty API
 * calls available per minute &mdash; the scarcest resource in the system &mdash; to be told
 * something we already knew.
 */
@Service
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class FilingPlanner {

    private static final Logger log = LoggerFactory.getLogger(FilingPlanner.class);

    private final JdbcTemplate jdbc;
    private final TinCryptoService tinCrypto;
    private final AttentionService attention;

    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public FilingPlanner(JdbcTemplate jdbc, TinCryptoService tinCrypto, AttentionService attention,
                         org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tinCrypto = tinCrypto;
        this.attention = attention;
        this.transactionManager = transactionManager;
    }

    /**
     * @param created    new filing rows
     * @param ready      eligible to transmit
     * @param blocked    owed but not transmittable as things stand
     * @param frozen     skipped because they are already in flight (see the freeze rule)
     */
    public record PlanResult(long created, long ready, long blocked, long frozen) {
    }

    /**
     * Creates or refreshes filings for every vendor determined to require a form.
     *
     * <h2>The freeze rule</h2>
     *
     * <p>Filings in {@code BATCHED} or {@code SUBMITTED_UNACKNOWLEDGED} are <b>not</b>
     * updated, even if determination now says a different amount. Their idempotency key was
     * derived from the old content; rewriting the amount underneath an in-flight batch would
     * mean shipping one number and recording another.
     *
     * <p>This is the Part 1 &times; Part 3 seam that most designs never notice: a revised
     * export arriving at 2 a.m. during an overnight run. Those filings are counted as
     * {@code frozen} and raise {@link AttentionType#AMENDED_DATA_FOR_INFLIGHT_FILING} rather
     * than being silently ignored &mdash; the data really did change, and a person needs to
     * decide whether a correction is owed once the original settles.
     */
    public PlanResult planFilings(int taxYear) {
        return inTransaction("transmission:plan-filings", false, () -> planFilingsInTransaction(taxYear));
    }

    private PlanResult planFilingsInTransaction(int taxYear) {
        long firmId = FirmContext.require();

        List<Map<String, Object>> determinations = jdbc.queryForList("""
                select vd.client_id, vd.vendor_key, vd.display_name, vd.reportable_cents,
                       vd.withholding_cents, vd.tin_status, vd.tin_last4, vd.run_id,
                       v.tin_ct, v.tin_key_ver, v.tin_bidx, v.tin_status as vendor_tin_status,
                       c.ein_ct, c.client_ref
                  from app.vendor_determination vd
                  join app.client c on c.id = vd.client_id
                  left join app.vendor v on v.id = vd.vendor_id
                 where vd.valid_to = 'infinity'
                   and vd.tax_year = ?
                   and vd.form_required
                """, taxYear);

        long created = 0;
        long ready = 0;
        long blocked = 0;
        long frozen = 0;
        long amended = 0;

        for (Map<String, Object> row : determinations) {
            long clientId = ((Number) row.get("client_id")).longValue();
            String vendorKey = (String) row.get("vendor_key");
            UUID filingId = IdempotencyKey.filingId(firmId, clientId, taxYear, vendorKey);

            // What determination says this filing SHOULD contain. Computed before the freeze
            // check, not after, so the two can actually be compared -- which is the whole
            // point of the check below.
            Preflight preflight = preflight(row, firmId, clientId);
            FilingState state = preflight.passed() ? FilingState.READY_TO_TRANSMIT : FilingState.BLOCKED;

            byte[] contentHash = IdempotencyKey.contentHash(
                    filingId, 1, taxYear,
                    "PAYER-EIN",
                    preflight.tinPlaintext() == null ? "" : preflight.tinPlaintext(),
                    "EIN",
                    (String) row.get("display_name"),
                    ((Number) row.get("reportable_cents")).longValue(),
                    ((Number) row.get("withholding_cents")).longValue());

            // THE FREEZE RULE.
            //
            // Sealing freezes content: once a filing is batched or in flight, its
            // content_hash is baked into an idempotency key the endpoint may already have
            // seen, so re-determination must not rewrite it. What it may do is record that
            // the data changed underneath, so a person can decide whether a correction is
            // owed once the original settles.
            //
            // THE COMPARISON IS THE POINT, AND IT WAS MISSING. The check used to fire on
            // state alone -- "is this filing in flight?" -- and raise an amendment whether or
            // not anything had actually been amended. Re-running `file` is the NORMAL
            // operating mode: drain twenty calls, wait for the rate window, drain again. So
            // the second run flagged every in-flight filing, and a six-round run against this
            // corpus produced 9,199 attention items, none of them real.
            //
            // The cost was not noise for its own sake. It made every one of 250 clients
            // NEEDS_ATTENTION, which is precisely the lie of omission the morning-after page's
            // priority ordering exists to prevent: a flag that fires on everything tells a
            // reader nothing, and the exception list becomes the thing it was built to avoid.
            Existing existing = jdbc.query(
                    "select state, content_hash from app.filing where id = ?",
                    rs -> rs.next() ? new Existing(rs.getString(1), rs.getBytes(2)) : null,
                    filingId);

            if (existing != null
                    && !FilingState.MUTABLE_BY_DETERMINATION.contains(
                            FilingState.valueOf(existing.state()))) {
                frozen++;

                if (!java.util.Arrays.equals(contentHash, existing.contentHash())) {
                    // Genuinely amended: we shipped one figure and the data now says another.
                    amended++;
                    attention.raise(AttentionType.AMENDED_DATA_FOR_INFLIGHT_FILING,
                            "FILING", filingId.toString(), clientId,
                            Map.of("vendorKey", vendorKey,
                                   "state", existing.state(),
                                   "newReportableCents", row.get("reportable_cents")));
                }
                continue;
            }

            int affected = jdbc.update("""
                    insert into app.filing (
                        id, firm_id, client_id, tax_year, vendor_key, state, content_hash,
                        amount_cents, withholding_cents, recipient_name,
                        recipient_tin_ct, recipient_tin_bidx, tin_last4, tin_status,
                        determination_run_id)
                    values (?, app.current_firm_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (firm_id, id) do update
                       set state             = excluded.state,
                           content_hash      = excluded.content_hash,
                           amount_cents      = excluded.amount_cents,
                           withholding_cents = excluded.withholding_cents,
                           recipient_name    = excluded.recipient_name,
                           recipient_tin_ct  = excluded.recipient_tin_ct,
                           tin_last4         = excluded.tin_last4,
                           tin_status        = excluded.tin_status,
                           determination_run_id = excluded.determination_run_id,
                           state_changed_at  = clock_timestamp()
                     where app.filing.content_hash is distinct from excluded.content_hash
                        or app.filing.state is distinct from excluded.state
                    """,
                    filingId, clientId, taxYear, vendorKey, state.name(), contentHash,
                    ((Number) row.get("reportable_cents")).longValue(),
                    ((Number) row.get("withholding_cents")).longValue(),
                    row.get("display_name"),
                    row.get("tin_ct"), row.get("tin_bidx"), row.get("tin_last4"),
                    row.get("tin_status"),
                    row.get("run_id"));

            if (affected > 0) {
                created++;
            }
            if (state == FilingState.READY_TO_TRANSMIT) {
                ready++;
            } else {
                blocked++;
                attention.raise(preflight.attentionType(), "FILING", filingId.toString(), clientId,
                        Map.of("vendorKey", vendorKey,
                               "reason", preflight.reason(),
                               "displayName", String.valueOf(row.get("display_name"))));
            }
        }

        // `frozen` and `amended` are reported separately on purpose: the first is "in flight,
        // left alone", which is routine, and the second is "in flight AND the data moved",
        // which is a person's decision. Collapsing them is what produced 9,199 false alarms.
        log.info("phase=PLAN_FILINGS tax_year={} determinations={} created={} ready={} "
                 + "blocked={} frozen={} amended={}",
                taxYear, determinations.size(), created, ready, blocked, frozen, amended);

        return new PlanResult(created, ready, blocked, frozen);
    }

    /** The sealed filing's state and content, for the freeze comparison. */
    private record Existing(String state, byte[] contentHash) {
    }

    // =================================================================================
    // Preflight
    // =================================================================================

    private record Preflight(boolean passed, String reason, AttentionType attentionType,
                             String tinPlaintext) {

        static Preflight ok(String tin) {
            return new Preflight(true, null, null, tin);
        }

        static Preflight fail(String reason, AttentionType type) {
            return new Preflight(false, reason, type, null);
        }
    }

    /**
     * Decides whether this filing can be transmitted as things stand.
     *
     * <p>Every check here is something the IRS would certainly reject. Sending them anyway
     * would waste one of twenty calls per minute to be told what we already know &mdash; and
     * on February 1, with a backlog, that budget is the binding constraint on the whole
     * system.
     */
    private Preflight preflight(Map<String, Object> row, long firmId, long clientId) {
        String tinStatus = (String) row.get("tin_status");

        if (!"PRESENT".equals(tinStatus)) {
            // The obligation stands; only the transmission is blocked. A W-9 needs collecting.
            return Preflight.fail("no usable TIN (" + tinStatus + ")", AttentionType.VENDOR_MISSING_TIN);
        }

        byte[] ciphertext = (byte[]) row.get("tin_ct");
        byte[] blindIndex = (byte[]) row.get("tin_bidx");
        Number keyVersion = (Number) row.get("tin_key_ver");

        if (ciphertext == null || blindIndex == null || keyVersion == null) {
            return Preflight.fail("TIN recorded as present but no ciphertext stored",
                    AttentionType.PREFLIGHT_VALIDATION_FAILED);
        }

        // One of exactly two places plaintext is produced. The other is the audited human
        // "reveal" action.
        Tin tin;
        try {
            tin = tinCrypto.decrypt(firmId, clientId, blindIndex, ciphertext, keyVersion.intValue());
        } catch (RuntimeException e) {
            return Preflight.fail("TIN could not be decrypted",
                    AttentionType.PREFLIGHT_VALIDATION_FAILED);
        }

        if (tin.hasInvalidPrefix()) {
            // The IRS rejects these outright. Known in advance, so never transmitted.
            return Preflight.fail("TIN begins 000", AttentionType.PREFLIGHT_VALIDATION_FAILED);
        }

        long amount = ((Number) row.get("reportable_cents")).longValue();
        long withholding = ((Number) row.get("withholding_cents")).longValue();
        if (amount <= 0 && withholding <= 0) {
            // Refunds exceeded payments. Determination already raised NEGATIVE_REPORTABLE;
            // this stops it reaching the wire.
            return Preflight.fail("non-positive reportable amount",
                    AttentionType.PREFLIGHT_VALIDATION_FAILED);
        }

        return Preflight.ok(tin.plaintextForTransmission());
    }

    /**
     * Explicit boundary, rather than {@code @Transactional}.
     *
     * <p>The annotation works through a proxy, so it is silently inert on a self-invocation --
     * and it cannot set a transaction NAME, which is what {@code FirmTransactionManager} reads
     * to decide whether to stamp {@code app.current_firm_id}. Both properties are load-bearing
     * here, so the boundary is written out. {@code ArchitectureTest} keeps it that way.
     */
    private <T> T inTransaction(String name, boolean readOnly, java.util.function.Supplier<T> body) {
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setName(name);
        definition.setReadOnly(readOnly);
        return new org.springframework.transaction.support.TransactionTemplate(
                transactionManager, definition).execute(status -> body.get());
    }
}
