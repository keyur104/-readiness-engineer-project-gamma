package com.soraban.readiness.determination;

import com.soraban.readiness.ledger.EntryType;
import com.soraban.readiness.ledger.ExpenseClass;
import com.soraban.readiness.seed.Xoshiro256StarStar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the SQL classifier and the Java classifier over the same inputs and asserts they
 * agree on every single one.
 *
 * <h2>Why this is worth more than a suite of example tests</h2>
 *
 * <p>Tests encode the same understanding the implementation does. If I misread the brief,
 * I will write a test that agrees with my misreading and it will pass. Example-based tests
 * for a rules engine therefore mostly prove the rules were <em>implemented as intended</em>,
 * not that the intent was right &mdash; and they only cover the combinations someone thought
 * to enumerate.
 *
 * <p>Two independent implementations that must agree is a genuinely different kind of
 * evidence. {@link PaymentClassifier} is a chain of {@code if} statements over primitives;
 * {@link DeterminationEngine#CLASSIFICATION_CASE_SQL} is a {@code CASE} expression evaluated
 * by PostgreSQL. For both to be wrong <em>and</em> agree, the same mistake would have to be
 * made twice, in two languages, at two levels of abstraction. That is not impossible, but it
 * is a far higher bar than "the test passes".
 *
 * <p>It also covers combinations no one would think to write down. The generator explores
 * the whole cross-product &mdash; including the ones that look absurd and are exactly where
 * bugs live: a void card refund dated outside the tax year, a zero-amount payment carrying
 * backup withholding, a negative non-services payment.
 *
 * <h2>The test uses the production SQL, not a copy of it</h2>
 *
 * <p>{@code CLASSIFICATION_CASE_SQL} is a shared constant precisely so this test cannot
 * drift from what actually runs. A pasted duplicate would keep passing after someone edited
 * the engine, while confirming agreement between two things neither of which is production.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClassifierDifferentialIT {

    private static final int TAX_YEAR = 2025;
    private static final int SAMPLE_SIZE = 20_000;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    /** One generated payment, in the shape both classifiers consume. */
    private record Sample(int id, int taxYear, String entryType, String expenseClass,
                          boolean cardOrTpso, long amountCents, long withholdingCents) {
    }

    @Test
    @DisplayName("SQL and Java classifiers agree on 20,000 generated payments, "
               + "including every awkward combination")
    void classifiersAgreeOnEveryInput() {
        List<Sample> samples = generateSamples();

        // Deliberately assert coverage before asserting agreement. A generator that happened
        // to produce only ordinary payments would make the test pass while exercising one
        // branch, and "20,000 cases passed" would be a misleading thing to report.
        assertCoversEveryDisposition(samples);

        Map<Integer, String> fromSql = classifyInPostgres(samples);

        List<String> disagreements = new ArrayList<>();
        for (Sample sample : samples) {
            Disposition java = PaymentClassifier.classify(
                    TAX_YEAR, sample.taxYear(),
                    EntryType.valueOf(sample.entryType()),
                    ExpenseClass.valueOf(sample.expenseClass()),
                    sample.cardOrTpso(), sample.amountCents(), sample.withholdingCents());

            String sql = fromSql.get(sample.id());

            if (!java.name().equals(sql)) {
                disagreements.add(
                        "%s -> java=%s sql=%s".formatted(describe(sample), java.name(), sql));
            }
        }

        assertThat(disagreements)
                .as("the Java classifier and the production SQL must agree on every input; "
                    + "a disagreement means one of them has misread the rules")
                .isEmpty();
    }

    @Test
    @DisplayName("the card check precedes the reversal check in BOTH implementations")
    void cardRefundIsExcludedRatherThanCounted() {
        // The single most consequential ordering in the rule set, pinned explicitly because
        // a property test can only prove the two implementations agree -- it cannot prove
        // they agree on the RIGHT answer. This says what the right answer is.
        //
        // A refund of a card payment carries payment_method = credit_card itself. Checking
        // "is the amount negative?" first would COUNT the refund while EXCLUDING the payment
        // it reverses, dragging the vendor's reportable total below any amount they were
        // actually paid -- with no error, no exception, and a plausible-looking figure.
        Disposition cardRefund = PaymentClassifier.classify(
                TAX_YEAR, TAX_YEAR, EntryType.REFUND, ExpenseClass.SERVICES,
                true, -50_000L, 0L);

        assertThat(cardRefund)
                .as("a card refund is excluded symmetrically with the card payment it reverses, "
                    + "never counted as a reduction")
                .isEqualTo(Disposition.EXCLUDED_CARD_TPSO);

        Disposition nonCardRefund = PaymentClassifier.classify(
                TAX_YEAR, TAX_YEAR, EntryType.REFUND, ExpenseClass.SERVICES,
                false, -50_000L, 0L);

        assertThat(nonCardRefund)
                .as("a non-card refund does reduce the year's net")
                .isEqualTo(Disposition.COUNTED_REVERSAL);
    }

    @Test
    @DisplayName("an out-of-year payment is excluded but still classified, never dropped")
    void outOfYearIsVisibleRatherThanAbsent() {
        Disposition previousYear = PaymentClassifier.classify(
                TAX_YEAR, TAX_YEAR - 1, EntryType.PAYMENT, ExpenseClass.SERVICES,
                false, 90_000L, 0L);

        // It must produce a disposition rather than being filtered out upstream: a payment
        // that silently vanishes from the explanation is indistinguishable from one that was
        // never imported, and that difference matters to whoever is reconciling against the
        // client's own books.
        assertThat(previousYear).isEqualTo(Disposition.EXCLUDED_OUT_OF_TAX_YEAR);
        assertThat(previousYear.isCounted()).isFalse();
    }

    // =================================================================================
    // Generation
    // =================================================================================

    /**
     * Explores the cross-product, weighted toward the awkward corners.
     *
     * <p>Uses the project's own PRNG with a fixed seed, so a failure is reproducible exactly
     * rather than "it went red on CI once".
     */
    private List<Sample> generateSamples() {
        Xoshiro256StarStar rng = new Xoshiro256StarStar(20250820L);
        EntryType[] entryTypes = EntryType.values();
        ExpenseClass[] expenseClasses = ExpenseClass.values();

        List<Sample> samples = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int year = switch (rng.nextInt(10)) {
                case 0 -> TAX_YEAR - 1;
                case 1 -> TAX_YEAR + 1;
                default -> TAX_YEAR;
            };

            // Amounts include zero and negatives frequently, because those are the boundaries
            // the disposition order actually turns on.
            long amount = switch (rng.nextInt(8)) {
                case 0 -> 0L;
                case 1, 2 -> -rng.nextLong(1, 500_000);
                default -> rng.nextLong(1, 500_000);
            };

            // Withholding on a zero-amount row is nonsense in the domain and is exactly the
            // combination that distinguishes EXCLUDED_ZERO_AMOUNT from COUNTED.
            long withholding = rng.nextBoolean(0.15) ? rng.nextLong(1, 50_000) : 0L;

            samples.add(new Sample(i, year,
                    rng.pick(entryTypes).name(),
                    rng.pick(expenseClasses).name(),
                    rng.nextBoolean(0.25),
                    amount, withholding));
        }
        return samples;
    }

    private void assertCoversEveryDisposition(List<Sample> samples) {
        var seen = new java.util.HashSet<Disposition>();
        for (Sample sample : samples) {
            seen.add(PaymentClassifier.classify(
                    TAX_YEAR, sample.taxYear(),
                    EntryType.valueOf(sample.entryType()),
                    ExpenseClass.valueOf(sample.expenseClass()),
                    sample.cardOrTpso(), sample.amountCents(), sample.withholdingCents()));
        }
        assertThat(seen)
                .as("the generated sample must reach every branch, or agreement proves little")
                .containsExactlyInAnyOrder(Disposition.values());
    }

    // =================================================================================
    // The SQL side
    // =================================================================================

    /**
     * Applies the production {@code CASE} to the samples inside one transaction.
     *
     * <p>One transaction throughout: the work table lives in {@code stg}, and creating it in
     * one transaction and reading it in another would risk a different pooled connection.
     */
    private Map<Integer, String> classifyInPostgres(List<Sample> samples) {
        return inSystemTransaction(() -> {
            String table = "classifier_diff_" + System.nanoTime();

            jdbc.execute("""
                    create unlogged table stg.%s (
                      id int, tax_year smallint, entry_type text, expense_class text,
                      is_card_or_tpso boolean, amount_cents bigint, withholding_cents bigint)
                    """.formatted(table));

            List<Object[]> rows = samples.stream()
                    .map(s -> new Object[]{s.id(), s.taxYear(), s.entryType(), s.expenseClass(),
                                           s.cardOrTpso(), s.amountCents(), s.withholdingCents()})
                    .toList();

            jdbc.batchUpdate("insert into stg.%s values (?, ?, ?, ?, ?, ?, ?)".formatted(table), rows);

            // THE production expression, not a copy of it.
            List<Map<String, Object>> classified = jdbc.queryForList("""
                    select r.id, %s as disposition from stg.%s r
                    """.formatted(DeterminationEngine.CLASSIFICATION_CASE_SQL, table), TAX_YEAR);

            jdbc.execute("drop table stg." + table);

            Map<Integer, String> byId = new java.util.HashMap<>(classified.size());
            for (Map<String, Object> row : classified) {
                byId.put(((Number) row.get("id")).intValue(), (String) row.get("disposition"));
            }
            return byId;
        });
    }

    private static String describe(Sample s) {
        return "year=%d entry=%s class=%s card=%s amount=%d withholding=%d".formatted(
                s.taxYear(), s.entryType(), s.expenseClass(),
                s.cardOrTpso(), s.amountCents(), s.withholdingCents());
    }

    private <T> T inSystemTransaction(Supplier<T> body) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        // stg carries no tenant data and no RLS, so this legitimately needs no firm context.
        definition.setName("system:classifier-differential");
        return new TransactionTemplate(transactionManager, definition).execute(status -> body.get());
    }
}
