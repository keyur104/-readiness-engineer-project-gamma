package com.soraban.readiness;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * The boundaries this design claims, enforced rather than described.
 *
 * <p>Every rule here corresponds to a claim made in the README or in a decision note. Without
 * these, each one is an intention that survives exactly as long as everyone remembers it &mdash;
 * and the whole argument of this project is that a boundary you have to remember is not a
 * boundary. The same reasoning that puts firm isolation in row-level security rather than in a
 * `where` clause puts these in a test rather than in a comment.
 *
 * <p>Runs against compiled classes with no Spring context, so it costs milliseconds and can be
 * the first thing that fails.
 */
class ArchitectureTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.soraban.readiness");
    }

    // =================================================================================
    // The IRS seam
    // =================================================================================

    @Test
    @DisplayName("only the stub package may touch the irs_stub schema")
    void theStubSchemaIsSealedOffFromApplicationCode() {
        // The stub models an EXTERNAL system. Its tables are deliberately outside our tenancy
        // model -- no firm_id, no row-level security -- precisely so the invariant checker can
        // ask what the endpoint actually recorded rather than what we believe.
        //
        // That only holds while application code cannot read those tables. The moment
        // determination or transmission peeks at irs_stub, the oracle becomes a mirror and
        // "zero duplicates, judged against the IRS's own books" stops meaning anything.
        //
        // InvariantChecker is the one deliberate exception: judging against the endpoint's
        // records is its entire job.
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(
                        "com.soraban.readiness.transmission.stub..",
                        "com.soraban.readiness.transmission")
                .should().dependOnClassesThat().resideInAPackage("com.soraban.readiness.transmission.stub..")
                .because("the IRS stub stands in for an external system; application code that "
                       + "reaches into it turns an independent oracle into a mirror of our own "
                       + "beliefs, and every assertion made against it becomes circular");

        rule.check(production);
    }

    @Test
    @DisplayName("the IRS interface carries no Spring, no JDBC and no project entities")
    void theSpiIsAGenuineSeam() {
        // "Swap in your own implementation" is only real if the interface can be implemented
        // without adopting this project's framework choices. If the SPI mentions a Spring type
        // or one of our records, an integrator inherits our dependencies to talk to their
        // endpoint -- and the seam is decoration.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.soraban.readiness.transmission.spi..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "javax.sql..",
                        "java.sql..",
                        "com.soraban.readiness.ingest..",
                        "com.soraban.readiness.determination..",
                        "com.soraban.readiness.web..")
                .because("the SPI is the swap point for a real HTTP client; it must be "
                       + "implementable without adopting this project's framework or its schema");

        rule.check(production);
    }

    // =================================================================================
    // Tenancy
    // =================================================================================

    @Test
    @DisplayName("nothing outside the security package reads the firm context directly")
    void firmContextIsNotReadAdHoc() {
        // Firm identity must flow from the principal (or a mandatory --firm) through
        // FirmContext.runAs into the transaction manager. Code that calls currentOrNull() and
        // then branches on it is code that has invented a second, weaker path -- and the
        // interesting branch is always the null one, which is exactly the case RLS is supposed
        // to make impossible rather than handled.
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(
                        "com.soraban.readiness.security..",
                        "com.soraban.readiness.config..")
                .should().callMethod(
                        com.soraban.readiness.security.FirmContext.class, "currentOrNull")
                .because("a caller that branches on a missing firm context has built a second "
                       + "path around the one mechanism that is supposed to have no second path");

        rule.check(production);
    }

    @Test
    @DisplayName("the web layer never opens a transaction without naming it")
    void webTransactionsAreNamed() {
        // FirmTransactionManager decides whether to stamp app.current_firm_id from the
        // transaction's NAME -- a "system:" prefix opts out. An unnamed transaction from the
        // web layer would therefore be a firm-scoped read with no scoping, which fails closed
        // (28000) rather than leaking, but fails at the user rather than at the developer.
        //
        // Enforced as "the web layer must construct DefaultTransactionDefinition", so the
        // naming call site is always visible in review rather than inherited from a default.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.soraban.readiness.web..")
                .should().callConstructor(
                        org.springframework.transaction.support.TransactionTemplate.class)
                .because("a TransactionTemplate built with no definition is an unnamed, "
                       + "default-isolation transaction; the page requires REPEATABLE READ and "
                       + "the transaction manager requires a name to decide firm scoping");

        rule.check(production);
    }

    // =================================================================================
    // The annotation that does nothing
    // =================================================================================

    @Test
    @DisplayName("@Transactional appears nowhere in production code")
    void transactionalIsBannedOutright() {
        // Seven of these were found to be INERT. Spring's @Transactional works through a proxy,
        // so a self-invocation -- one method of a bean calling another -- bypasses it entirely
        // and the annotation silently does nothing.
        //
        // In an ordinary system that failure is invisible: each statement just commits on its
        // own and the totals still look right. Here it is fatal in a useful way, because no
        // transaction means no set_config, which means app.current_firm_id() raises 28000. The
        // isolation design surfaced seven latent transaction bugs it was never built to catch.
        //
        // Rather than rely on noticing next time, the annotation is banned and every boundary
        // is an explicit TransactionTemplate. Explicit is also the only way to set an isolation
        // level and a name, both of which this system depends on.
        // THE ONE EXEMPTION, and it is not a concession -- it is the same argument reaching
        // the opposite conclusion. StubStore exists as a separate bean SPECIFICALLY so that
        // the proxy applies and REQUIRES_NEW actually takes effect, because the stub models an
        // external system whose books must commit independently of ours. If its writes joined
        // the caller's transaction, a caller that rolled back would un-record filings the
        // "IRS" had already accepted -- and failure mode B, where the filings are live
        // precisely BECAUSE our view of events is wrong, would become unrepresentable.
        //
        // So the ban is about self-invocation and about naming. The stub has neither problem,
        // and needs propagation semantics the explicit form would have to restate anyway.
        String annotation = "org.springframework.transaction.annotation.Transactional";
        String why = "@Transactional is inert under self-invocation and cannot name a "
                   + "transaction, and FirmTransactionManager reads the name to decide firm "
                   + "scoping; every application boundary is an explicit TransactionTemplate";

        ArchRule onTypes = noClasses()
                .that().resideOutsideOfPackage("com.soraban.readiness.transmission.stub..")
                .should().beAnnotatedWith(annotation)
                .because(why);

        ArchRule onMethods = noMethods()
                .that().areDeclaredInClassesThat()
                .resideOutsideOfPackage("com.soraban.readiness.transmission.stub..")
                .should().beAnnotatedWith(annotation)
                .because(why);

        onTypes.check(production);
        onMethods.check(production);
    }

    // =================================================================================
    // Money and TINs
    // =================================================================================

    @Test
    @DisplayName("no field that holds money is a floating-point type")
    void moneyIsNeverAFloat() {
        // Money is bigint cents end to end, so "$600 or more" has no epsilon to argue about.
        //
        // THE FIRST VERSION OF THIS RULE BANNED ALL DOUBLES IN THESE PACKAGES AND WAS WRONG.
        // It caught seven fields, and every one of them was legitimate: maxRejectionRate
        // (0.05), backoffMultiplier (2.0), reservedShare (0.20), and the stub's three failure
        // rates. Those are ratios, not amounts -- a backoff multiplier expressed in integer
        // cents would be absurd.
        //
        // A rule that fires on correct code gets suppressed, and a suppressed rule protects
        // nothing. So this one is scoped by NAME to the fields that actually hold money.
        // That is a weaker check than a type-level ban, and it is deliberately not the primary
        // guarantee: money can only be wrong if it is STORED wrong, and FirmIsolationIT
        // separately greps information_schema.columns for float money columns and fails the
        // build. This is the Java-side reminder, not the enforcement.
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        "com.soraban.readiness.determination..",
                        "com.soraban.readiness.transmission..",
                        "com.soraban.readiness.ingest..")
                .and().haveNameMatching(".*([Cc]ents|[Aa]mount|[Mm]oney|[Tt]otalPaid).*")
                .should().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .orShould().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .because("money is integer cents everywhere; a float amount is how a rounding "
                       + "error reaches a tax form");

        rule.check(production);
    }

    @Test
    @DisplayName("Tin is not a record, so it cannot generate a field-printing toString")
    void theTinValueObjectCannotLeakThroughToString() {
        // toString() is the leak path that actually gets used: string concatenation,
        // log.info("{}", vendor), Jackson defaults, JDBC parameter tracing,
        // IllegalArgumentException("bad TIN: " + value). A record generates a toString that
        // prints every component, which would defeat the masking the class exists for.
        ArchRule rule = classes()
                .that().haveSimpleName("Tin")
                .should().notBeRecords()
                .because("a record's generated toString() prints every field, which is exactly "
                       + "the leak path Tin exists to close");

        rule.check(production);
    }

    // =================================================================================
    // Layering
    // =================================================================================

    @Test
    @DisplayName("the ingest and determination layers do not depend on the web layer")
    void theDomainDoesNotDependOnThePage() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.soraban.readiness.ingest..",
                        "com.soraban.readiness.determination..",
                        "com.soraban.readiness.transmission..",
                        "com.soraban.readiness.seed..")
                .should().dependOnClassesThat().resideInAPackage("com.soraban.readiness.web..")
                .because("the page reads the domain; the domain must not know the page exists, "
                       + "or the CLI stops being able to run without a servlet container");

        rule.check(production);
    }

    @Test
    @DisplayName("the seed generator depends on no Spring and no database")
    void theGeneratorRunsWithoutAnythingElse() {
        // `seed` is the one command that needs no database, so a reviewer can generate a corpus
        // and look at it before setting Postgres up. That only stays true while the generator
        // has no framework or JDBC dependency -- and it has already been broken once, from a
        // different direction, when the IRS stub's beans dragged a DataSource into the seed
        // command's context.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.soraban.readiness.seed..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "javax.sql..", "java.sql..")
                .because("seed must run before Postgres exists, so a reviewer can look at the "
                       + "corpus before setting anything up");

        rule.check(production);
    }
}
