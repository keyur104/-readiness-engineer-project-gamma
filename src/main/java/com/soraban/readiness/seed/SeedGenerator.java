package com.soraban.readiness.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.soraban.readiness.ingest.SourceDialect;
import com.soraban.readiness.ledger.EntryType;
import com.soraban.readiness.ledger.ExpenseClass;
import com.soraban.readiness.ledger.PaymentMethod;
import com.soraban.readiness.support.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates the seed corpus: roughly a million ledger lines across ~500 business clients
 * and two firms, deterministic given a seed.
 *
 * <h2>Writes to disk rather than streaming into the importer</h2>
 *
 * <p>The file is the artifact under test. Three of Part 1's five requirements depend on it
 * existing as bytes: importing the same file twice must change nothing (so there has to be
 * a stable "same file"), a revised export must update only what changed (so there has to be
 * a durable "before"), and the reviewer needs something to re-import by hand. Streaming
 * straight into the importer would make all three untestable.
 *
 * <h2>Output layout</h2>
 *
 * <pre>
 *   data/
 *     firm-northstar/
 *       manifest.json          declared coverage, per-file sha256, seed, revision
 *       clients.csv
 *       payments-quickbooks.csv
 *       payments-xero.csv
 *       payments-spreadsheet.csv
 *     firm-harborline/
 *       ...
 *     fixtures.json            planted cases and defects, with expected outcomes
 * </pre>
 *
 * <p>{@code fixtures.json} sits above the firm directories because it spans both, and
 * because it is emphatically not part of any export &mdash; it is the test suite's index,
 * and an importer that read it would be cheating.
 */
public class SeedGenerator {

    private static final Logger log = LoggerFactory.getLogger(SeedGenerator.class);

    private final SeedConfig config;
    private final RandomStreams streams;
    private final ObjectMapper json;

    /**
     * What the revision changed, accumulated across clients.
     *
     * <p>Written to revision-manifest.json so the incremental-import test asserts against
     * numbers the generator stated in advance, rather than against whatever the importer
     * happened to report. If both sides derived the expectation from the same place, the
     * test would only prove the importer agrees with itself.
     */
    private final Map<String, long[]> revisionCounts = new LinkedHashMap<>();

    /** {inserted, updated, tombstoned} for one firm. */
    private long[] revisionCountsFor(String firm) {
        return revisionCounts.computeIfAbsent(firm, f -> new long[3]);
    }

    public SeedGenerator(SeedConfig config) {
        this.config = config;
        this.streams = new RandomStreams(config.seed());
        this.json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * @param totalRows   ledger lines written across every firm
     * @param rowsByFirm  per-firm counts
     * @param outputDir   the root that was written to
     * @param elapsedMs   wall clock
     */
    public record SeedResult(long totalRows, Map<String, Long> rowsByFirm, Path outputDir, long elapsedMs) {
    }

    // =================================================================================
    // Entry point
    // =================================================================================

    public SeedResult generate() throws IOException {
        long startedAt = System.nanoTime();

        Map<String, Map<Integer, List<FixtureCase>>> fixtureAssignments = assignFixtures();

        List<FixtureManifest.PlantedCase> plantedCases = new ArrayList<>();
        List<FixtureManifest.PlantedDefect> plantedDefects = new ArrayList<>();
        Map<String, Long> rowsByFirm = new LinkedHashMap<>();

        int clientsPerFirm = config.clientCount() / config.firms().size();
        int vendorTotal = 0;
        long rejectableTotal = 0;

        for (int firmIndex = 0; firmIndex < config.firms().size(); firmIndex++) {
            String firm = config.firms().get(firmIndex);

            FirmOutput output = generateFirm(
                    firm, firmIndex, clientsPerFirm,
                    fixtureAssignments.getOrDefault(firm, Map.of()),
                    plantedCases, plantedDefects);

            rowsByFirm.put(firm, output.rowCount());
            vendorTotal += output.vendorCount();
            rejectableTotal += output.rejectableCount();
        }

        long totalRows = rowsByFirm.values().stream().mapToLong(Long::longValue).sum();

        if (config.revision() > 0) {
            writeRevisionManifest();
        }

        writeFixtures(plantedCases, plantedDefects, new FixtureManifest.Aggregates(
                totalRows, rowsByFirm, config.clientCount(), vendorTotal,
                rejectableTotal, countExpectedFilings(plantedCases), caseCounts(plantedCases)));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        log.info("phase=SEED seed={} firms={} clients={} rows={} defects={} cases={} ms={} rows_per_sec={}",
                config.seed(), config.firms().size(), config.clientCount(), totalRows,
                plantedDefects.size(), plantedCases.size(), elapsedMs,
                elapsedMs > 0 ? totalRows * 1000 / elapsedMs : totalRows);

        return new SeedResult(totalRows, rowsByFirm, config.outputDir(), elapsedMs);
    }

    // =================================================================================
    // Fixture assignment
    // =================================================================================

    /**
     * Decides, deterministically and before any data is generated, which clients host which
     * planted cases.
     *
     * <p>Assignment happens up front rather than opportunistically during generation so
     * that it depends only on the seed &mdash; not on how many vendors a client happened to
     * get, or on generation order. That keeps it stable when unrelated generation code
     * changes.
     *
     * <p>Plantings alternate between firms by index, guaranteeing every case appears in
     * both books. That is what lets the isolation tests assert a case is visible from one
     * firm and invisible from the other, using the same fixture.
     */
    private Map<String, Map<Integer, List<FixtureCase>>> assignFixtures() {
        Map<String, Map<Integer, List<FixtureCase>>> assignments = new HashMap<>();
        int clientsPerFirm = config.clientCount() / config.firms().size();

        for (FixtureCase fixtureCase : FixtureCase.values()) {
            Xoshiro256StarStar rng = streams.get("fixtures/assign/" + fixtureCase.name());

            for (int planting = 0; planting < FixtureCase.PLANTINGS_PER_CASE; planting++) {
                String firm = config.firms().get(planting % config.firms().size());
                Map<Integer, List<FixtureCase>> firmAssignments =
                        assignments.computeIfAbsent(firm, f -> new HashMap<>());

                // Linear-probe away from a client that already hosts this case. Two
                // plantings of the same scenario in one client would sit side by side in
                // the output as visibly identical blocks -- and the whole premise is that
                // a planted case is indistinguishable from ordinary data. Probing rather
                // than re-rolling keeps the choice a pure function of the seed.
                int clientIndex = rng.nextInt(clientsPerFirm);
                for (int probe = 0; probe < clientsPerFirm; probe++) {
                    if (!firmAssignments.getOrDefault(clientIndex, List.of()).contains(fixtureCase)) {
                        break;
                    }
                    clientIndex = (clientIndex + 1) % clientsPerFirm;
                }

                firmAssignments.computeIfAbsent(clientIndex, c -> new ArrayList<>()).add(fixtureCase);
            }
        }
        return assignments;
    }

    private static Map<String, Integer> caseCounts(List<FixtureManifest.PlantedCase> cases) {
        Map<String, Integer> counts = new TreeMap<>();
        for (FixtureManifest.PlantedCase planted : cases) {
            counts.merge(planted.caseId(), 1, Integer::sum);
        }
        return counts;
    }

    private static long countExpectedFilings(List<FixtureManifest.PlantedCase> cases) {
        return cases.stream().filter(c -> c.expected().formRequired()).count();
    }

    // =================================================================================
    // Per-firm generation
    // =================================================================================

    private record FirmOutput(long rowCount, int vendorCount, long rejectableCount) {
    }

    private FirmOutput generateFirm(String firm,
                                    int firmIndex,
                                    int clientCount,
                                    Map<Integer, List<FixtureCase>> fixtures,
                                    List<FixtureManifest.PlantedCase> plantedCases,
                                    List<FixtureManifest.PlantedDefect> plantedDefects) throws IOException {

        Path firmDir = config.outputDir().resolve("firm-" + firm);
        List<GeneratedClient> clients = buildClients(firm, firmIndex, clientCount, fixtures);

        writeClients(firmDir, clients);

        // One writer per source system. Opened lazily so a firm with no spreadsheet clients
        // does not produce an empty spreadsheet file that the manifest then has to explain.
        Map<String, DeterministicCsvWriter> writers = new LinkedHashMap<>();
        Map<String, Long> lineNumbers = new HashMap<>();

        long rowCount = 0;
        int vendorCount = 0;
        long rejectableCount = 0;

        // Every year this export actually contains, so the manifest declares its real
        // coverage rather than assuming it equals the filing year. See ExportManifest.
        java.util.SortedSet<Integer> coveredTaxYears = new java.util.TreeSet<>();

        try {
            for (int i = 0; i < clients.size(); i++) {
                GeneratedClient client = clients.get(i);
                vendorCount += client.vendors().size();

                List<FixtureCase> clientFixtures = fixtures.getOrDefault(i, List.of());
                ClientRows produced = generateClientRows(firm, client, i, clientFixtures,
                                                         plantedCases, firmIndex);

                for (RowDestination destination : produced.rows()) {
                    DeterministicCsvWriter writer = writers.computeIfAbsent(destination.sourceSystem(), system -> {
                        try {
                            Path path = firmDir.resolve("payments-" + system.toLowerCase() + suffix());
                            DeterministicCsvWriter w = DeterministicCsvWriter.open(path);
                            // Each file carries the header names its own system would emit,
                            // so the importer's alias mapping is genuinely exercised.
                            w.writeHeader(SourceDialect.byId(system).outputHeaders(PaymentRow.HEADERS));
                            return w;
                        } catch (IOException e) {
                            throw new UncheckedIOWrapper(e);
                        }
                    });

                    // Line 1 is the header, so the first data row is line 2. Recorded because
                    // the rejection report cites file and line, and the test asserts on it.
                    long lineNumber = lineNumbers.merge(destination.sourceSystem(), 1L, Long::sum) + 1;

                    if (destination.rawLine() != null) {
                        writer.writeRawLine(destination.rawLine());
                    } else {
                        PaymentRow row = destination.row();
                        try {
                            coveredTaxYears.add(LocalDate.parse(row.paymentDate()).getYear());
                        } catch (RuntimeException ignored) {
                            // a planted unparseable-date defect contributes no year
                        }
                        writer.writeRow(renderFor(destination.sourceSystem(), row));
                    }
                    rowCount++;

                    if (destination.defect() != null) {
                        DefectClass defect = destination.defect();
                        if (defect.isRejection()) {
                            rejectableCount++;
                        }
                        plantedDefects.add(new FixtureManifest.PlantedDefect(
                                defect.name(), firm,
                                "payments-" + destination.sourceSystem().toLowerCase() + suffix(),
                                lineNumber, defect.reasonCode(), defect.outcome().name()));
                    }
                }
            }
        } catch (UncheckedIOWrapper e) {
            throw e.getCause();
        } finally {
            for (DeterministicCsvWriter writer : writers.values()) {
                writer.close();
            }
        }

        writeManifest(firmDir, firm, clients, writers, coveredTaxYears);
        return new FirmOutput(rowCount, vendorCount, rejectableCount);
    }

    private String suffix() {
        return config.gzip() ? ".csv.gz" : ".csv";
    }

    /**
     * Converts a canonical row into the target system's formatting.
     *
     * <p>Rows are constructed once in canonical form and rendered here, so there is a single
     * construction path and the dialect owns every formatting decision. A defect row that
     * deliberately carries an unparseable date or amount passes through unchanged &mdash;
     * re-rendering it would repair the very thing the fixture is testing.
     */
    private PaymentRow renderFor(String sourceSystem, PaymentRow row) {
        SourceDialect dialect = SourceDialect.byId(sourceSystem);
        if (dialect == SourceDialect.CANONICAL) {
            return row;
        }
        return row.renderedFor(
                iso -> {
                    try {
                        return dialect.renderDate(LocalDate.parse(iso));
                    } catch (RuntimeException e) {
                        return iso;   // a planted UNPARSEABLE_DATE defect; leave it broken
                    }
                },
                plain -> {
                    try {
                        return dialect.renderAmount(
                                com.soraban.readiness.support.Money.parseToCents(plain));
                    } catch (RuntimeException e) {
                        return plain; // a planted UNPARSEABLE_AMOUNT or SUB_CENT defect
                    }
                });
    }

    /** Lets the lambda inside {@code computeIfAbsent} propagate an IOException. */
    private static final class UncheckedIOWrapper extends RuntimeException {
        UncheckedIOWrapper(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }

    // =================================================================================
    // Clients and vendors
    // =================================================================================

    private List<GeneratedClient> buildClients(String firm, int firmIndex, int count,
                                               Map<Integer, List<FixtureCase>> fixtures) {
        Xoshiro256StarStar rng = streams.get("firm:" + firm + "/clients");

        // Row budgets are log-normal, then scaled so the corpus lands near the requested
        // total. Scaling after the fact keeps the *shape* of the distribution while making
        // the total predictable -- a realistic book has a few very large clients, and that
        // skew is what makes client-parallel scheduling non-trivial.
        long[] weights = new long[count];
        long weightSum = 0;
        for (int i = 0; i < count; i++) {
            weights[i] = rng.nextLogNormal(
                    SeedConfig.ROWS_PER_CLIENT_LOG_MEAN, SeedConfig.ROWS_PER_CLIENT_LOG_STDDEV,
                    SeedConfig.ROWS_PER_CLIENT_MIN, SeedConfig.ROWS_PER_CLIENT_MAX);
            weightSum += weights[i];
        }
        long firmTarget = config.targetLines() / config.firms().size();

        List<GeneratedClient> clients = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Xoshiro256StarStar clientRng = streams.get("firm:" + firm + "/client:" + pad(i));

            long targetRows = Math.max(SeedConfig.ROWS_PER_CLIENT_MIN,
                                       weights[i] * firmTarget / Math.max(1, weightSum));

            String clientRef = "C-" + pad(i);
            String legalName = clientRng.pick(Corpora.CLIENT_PREFIXES) + " "
                             + clientRng.pick(Corpora.CLIENT_TYPES);

            String[] cityState = clientRng.pick(Corpora.CITIES);
            String address = clientRng.nextInt(100, 9999) + " "
                           + clientRng.pick(Corpora.STREET_NAMES) + " "
                           + clientRng.pick(Corpora.STREET_TYPES);

            List<String> sourceSystems = pickSourceSystems(clientRng);

            List<GeneratedVendor> vendors = buildVendors(firm, i, clientRng, targetRows);

            clients.add(new GeneratedClient(
                    clientRef, legalName,
                    einFor(firmIndex, i),
                    address, cityState[0], cityState[1],
                    pad5(clientRng.nextInt(10_000, 99_999)),
                    sourceSystems, vendors, targetRows));
        }
        return clients;
    }

    /**
     * Roughly 2% of clients appear in two files, modelling a mid-year accounting-system
     * switch.
     *
     * <p>This is the case that stresses tombstone scoping: a QuickBooks export declaring
     * coverage of this client must not delete the client's spreadsheet-sourced rows merely
     * because they are absent from it.
     */
    private List<String> pickSourceSystems(Xoshiro256StarStar rng) {
        String[] systems = {"QUICKBOOKS", "XERO", "SPREADSHEET"};
        String primary = systems[rng.pickWeighted(new int[]{45, 80, 100})];

        if (rng.nextBoolean(SeedConfig.SYSTEM_SWITCH_RATE)) {
            String secondary = primary.equals("QUICKBOOKS") ? "XERO" : "QUICKBOOKS";
            return List.of(primary, secondary);
        }
        return List.of(primary);
    }

    private List<GeneratedVendor> buildVendors(String firm, int clientIndex, Xoshiro256StarStar rng,
                                              long targetRows) {
        Xoshiro256StarStar vendorRng = streams.get("firm:" + firm + "/client:" + pad(clientIndex) + "/vendors");

        // Vendor count SCALES WITH the client's payment volume, rather than being drawn
        // independently of it. A business making 15,000 payments a year deals with more
        // contractors than one making 200 -- and when this was independent, the big client's
        // payments piled onto the same ~40 vendors and produced contractors receiving 3,461
        // payments worth $2.5m. The tax logic was right; the vendor was not a real thing.
        //
        // The log-normal jitter is kept, around the scaled centre rather than a fixed 40, so
        // two clients of the same size still differ.
        double centre = Math.max(SeedConfig.VENDORS_PER_CLIENT_MIN,
                (double) targetRows / SeedConfig.PAYMENTS_PER_VENDOR_TARGET);

        int vendorCount = (int) vendorRng.nextLogNormal(
                Math.log(centre), 0.45,
                SeedConfig.VENDORS_PER_CLIENT_MIN, SeedConfig.VENDORS_PER_CLIENT_MAX);

        List<GeneratedVendor> vendors = new ArrayList<>(vendorCount);
        for (int v = 0; v < vendorCount; v++) {
            vendors.add(buildVendor(firm, clientIndex, v, vendorRng));
        }
        return vendors;
    }

    private GeneratedVendor buildVendor(String firm, int clientIndex, int vendorIndex,
                                        Xoshiro256StarStar rng) {
        // A meaningful share of vendors are sole proprietors paid under their own name --
        // the population whose TIN is an SSN, and therefore the population the whole
        // TIN-protection design exists for.
        boolean soleProprietor = rng.nextBoolean(0.25);

        String name;
        String tinType;
        if (soleProprietor) {
            boolean accented = rng.nextBoolean(0.12);
            String last = accented ? rng.pick(Corpora.ACCENTED_LAST_NAMES) : rng.pick(Corpora.LAST_NAMES);
            name = rng.pick(Corpora.FIRST_NAMES) + " " + last;
            tinType = "SSN";
        } else {
            name = rng.pick(Corpora.COMPANY_PREFIXES) + " " + rng.pick(Corpora.TRADES);
            if (rng.nextBoolean(0.45)) {
                name = name + " " + rng.pick(Corpora.LEGAL_SUFFIXES);
            }
            tinType = "EIN";
        }

        double tinRoll = rng.nextDouble();
        String tin = null;
        String tinFormat = "NONE";
        if (tinRoll < SeedConfig.TIN_PRESENT_RATE) {
            tin = generateTin(rng);
            tinFormat = soleProprietor ? "SSN_DASH" : "EIN_DASH";
        } else if (tinRoll < SeedConfig.TIN_PRESENT_RATE + SeedConfig.TIN_BLANK_RATE) {
            tinType = "";   // no TIN at all: ordinary data, becomes an exception if over threshold
        } else {
            tin = generateTin(rng);
            tinFormat = "RAW";
        }

        // A minority of vendors are recorded inconsistently. Case 1 mandates at least one
        // three-spelling vendor, but the corpus should contain organic variation too --
        // otherwise the only name variance in a million rows would be the planted kind.
        List<String> spellings = List.of(name);
        if (tin != null && rng.nextBoolean(0.08)) {
            spellings = spellingVariants(name, rng);
        }

        boolean withholding = rng.nextBoolean(SeedConfig.VENDOR_WITHHOLDING_RATE);

        return new GeneratedVendor(
                firm + "-" + clientIndex + "-" + vendorIndex,
                spellings, tin, tinFormat, tinType, withholding, soleProprietor, null);
    }

    /**
     * Produces the kind of variation a bookkeeper actually creates: case changes, a legal
     * suffix appearing or not, punctuation drifting. All of these must collapse to one
     * vendor when a TIN is present.
     */
    private List<String> spellingVariants(String name, Xoshiro256StarStar rng) {
        List<String> variants = new ArrayList<>(3);
        variants.add(name);
        variants.add(name.toUpperCase(java.util.Locale.ROOT) + " LLC");
        variants.add(name + ", L.L.C.");
        rng.shuffle(variants);
        return List.copyOf(variants);
    }

    /** Nine digits, never starting {@code 99} (reserved for fixtures) or {@code 000}. */
    private String generateTin(Xoshiro256StarStar rng) {
        int prefix = rng.nextInt(10, 98);
        int body = rng.nextInt(1_000_000, 9_999_999);
        return prefix + Integer.toString(body);
    }

    private String einFor(int firmIndex, int clientIndex) {
        int body = 1_000_000 + firmIndex * 100_000 + clientIndex * 37 % 100_000;
        return (20 + firmIndex) + "-" + body;
    }

    // =================================================================================
    // Payment rows
    // =================================================================================

    private record RowDestination(String sourceSystem, PaymentRow row, String rawLine, DefectClass defect) {
    }

    private record ClientRows(List<RowDestination> rows) {
    }

    private ClientRows generateClientRows(String firm,
                                          GeneratedClient client,
                                          int clientIndex,
                                          List<FixtureCase> fixtures,
                                          List<FixtureManifest.PlantedCase> plantedCases,
                                          int firmIndex) {

        Xoshiro256StarStar rng = streams.get("firm:" + firm + "/client:" + pad(clientIndex) + "/payments");
        Xoshiro256StarStar defectRng = streams.get("firm:" + firm + "/client:" + pad(clientIndex) + "/defects");

        List<RowDestination> rows = new ArrayList<>();
        String primarySystem = client.sourceSystems().getFirst();

        // ---- planted fixture cases ----------------------------------------------------
        int tinSeq = firmIndex * 500_000 + clientIndex * 100;
        int idSeq = 900_000 + clientIndex * 1_000;

        for (int f = 0; f < fixtures.size(); f++) {
            FixtureCase fixtureCase = fixtures.get(f);
            FixturePlanter.Planted planted = FixturePlanter.plant(
                    fixtureCase, client.clientRef(), primarySystem,
                    config.taxYear(), tinSeq + f * 10, idSeq + f * 20);

            for (PaymentRow row : planted.rows()) {
                rows.add(new RowDestination(primarySystem, row, null, null));
            }

            // The first planting of each case, corpus-wide, is the canonical instance the
            // focused test names. Later plantings are asserted in aggregate.
            boolean canonical = plantedCases.stream().noneMatch(c -> c.caseId().equals(fixtureCase.name()));

            GeneratedVendor first = planted.vendors().getFirst();
            plantedCases.add(new FixtureManifest.PlantedCase(
                    fixtureCase.name(), canonical, firm, client.clientRef(),
                    first.tin(), first.spellings(), planted.expected()));
        }

        // ---- ordinary payments ---------------------------------------------------------
        long remaining = Math.max(0, client.targetRows() - rows.size());
        List<GeneratedVendor> vendors = client.vendors();

        // Payment counts per vendor follow a Zipf-like skew: a client pays a handful of
        // vendors constantly and most of them once or twice. Uniform distribution would
        // put nearly every vendor over the threshold and make the corpus far less
        // interesting than a real book.
        // Softened from a pure harmonic series (1/(v+1)), where the top vendor took about a
        // quarter of the client's entire year. Still clearly skewed -- the head is the busiest
        // supplier and the tail is paid once -- but no longer absurd at the top.
        double[] weight = new double[vendors.size()];
        double weightSum = 0;
        for (int v = 0; v < vendors.size(); v++) {
            weight[v] = 1.0 / Math.pow(v + 1, SeedConfig.VENDOR_SHARE_EXPONENT);
            weightSum += weight[v];
        }

        int txnCounter = clientIndex * 100_000;

        long budget = remaining;
        for (int v = 0; v < vendors.size() && remaining > 0; v++) {
            long count = Math.max(1, Math.round(weight[v] / weightSum * budget));
            // The backstop. Scaling should keep this unreachable; if a very large client draws
            // unluckily, one contractor still cannot end up with a year of implausible volume.
            count = Math.min(count, SeedConfig.MAX_PAYMENTS_PER_VENDOR);
            count = Math.min(count, remaining);

            for (long p = 0; p < count; p++) {
                rows.add(emitPayment(vendors.get(v), client, primarySystem, ++txnCounter, rng, defectRng));
                remaining--;
            }
        }

        // Integer division above truncates every vendor's share, so the loop reliably
        // undershoots the budget -- by roughly one row per vendor, which across 40 vendors
        // is a 5-10% shortfall. Top up round-robin so the corpus actually lands near the
        // requested size; without this, `--lines=1000000` quietly produces ~930k.
        for (int v = 0; remaining > 0 && !vendors.isEmpty(); v++) {
            rows.add(emitPayment(vendors.get(v % vendors.size()), client, primarySystem,
                                 ++txnCounter, rng, defectRng));
            remaining--;
        }

        if (config.revision() > 0) {
            applyRevision(firm, client, clientIndex, rows);
        }

        // Scatter the planted cases through the client's ordinary activity. Without this
        // they sit in one contiguous block at the head of the file, which would make every
        // fixture trivially identifiable by position -- and the premise of the whole
        // planting design is that they are indistinguishable from ordinary data.
        streams.get("firm:" + firm + "/client:" + pad(clientIndex) + "/order").shuffle(rows);

        return new ClientRows(rows);
    }

    /**
     * Turns a base export into a revised one: the bookkeeper found a missed invoice,
     * corrected an amount, deleted a line that should not have been there, and finally
     * collected a W-9.
     *
     * <p>Applied to roughly one client in ten, so a revision touches a small slice of the
     * book. That proportion is the point of the exercise: the importer must update what
     * changed and leave the other ~90% of the corpus completely untouched &mdash; no heap
     * writes, no dirty marks, no re-determination.
     *
     * <h2>Why modifications only target rows with a native transaction id</h2>
     *
     * <p>Editing the amount on a QuickBooks or Xero row keeps its {@code source_txn_id}, so
     * the importer sees a clean UPDATE to the same row. Editing a <em>spreadsheet</em> row
     * changes the natural tuple its tier-2 identity is synthesized from, so it necessarily
     * reads as a delete plus an insert.
     *
     * <p>That is the documented tier-2 limitation, and it is real rather than hidden. But a
     * fixture asserting "M rows updated" should test the mechanism, not the limitation, so
     * amount edits are confined to tier-1 rows where the semantics are unambiguous.
     * Deletions and additions apply anywhere, since both are honest in either tier.
     */
    private void applyRevision(String firm, GeneratedClient client, int clientIndex,
                               List<RowDestination> rows) {
        Xoshiro256StarStar rng = streams.get(
                "firm:" + firm + "/client:" + pad(clientIndex) + "/revision:" + config.revision());

        if (!rng.nextBoolean(0.10) || rows.isEmpty()) {
            return;
        }

        String primarySystem = client.sourceSystems().getFirst();

        // 1. The missed invoice. New transaction ids that have never been seen, so these
        //    must arrive as INSERTs.
        int additions = rng.nextInt(1, 4);
        for (int i = 0; i < additions; i++) {
            GeneratedVendor vendor = client.vendors().get(rng.nextInt(client.vendors().size()));
            String txnId = switch (primarySystem) {
                case "QUICKBOOKS" -> "QB-REV" + config.revision() + "-" + clientIndex + "-" + i;
                case "XERO" -> "xer-rev%d-%04d-%d".formatted(config.revision(), clientIndex, i);
                default -> "";
            };
            PaymentRow added = PaymentRow.of(
                    primarySystem, txnId, client.clientRef(), vendor.primaryName(),
                    vendor.tinAsWritten(), vendor.tinType(),
                    LocalDate.of(config.taxYear(), 12, rng.nextInt(20, 32)),
                    rng.nextLogNormal(Math.log(60_000), 0.8, 5_000, 800_000),
                    PaymentMethod.CHECK, EntryType.PAYMENT, null, 0,
                    ExpenseClass.SERVICES, "Missed invoice found during year-end review");
            rows.add(new RowDestination(primarySystem, added, null, null));
            revisionCountsFor(firm)[0]++;
        }

        // 2. A corrected amount, on a tier-1 row only.
        int modifications = rng.nextInt(1, 3);
        for (int i = 0; i < modifications; i++) {
            int index = findTierOneRow(rows, rng);
            if (index < 0) {
                break;
            }
            PaymentRow original = rows.get(index).row();
            long corrected = Money.parseToCents(original.amount()) + rng.nextInt(100, 25_000);
            rows.set(index, new RowDestination(
                    rows.get(index).sourceSystem(),
                    original.with(PaymentRow.COL_AMOUNT, Money.toPlainString(corrected)),
                    null, null));
            revisionCountsFor(firm)[1]++;
        }

        // 3. A line the bookkeeper removed. Absent from the revised file, inside the
        //    manifest's declared scope, so the importer must tombstone it.
        int index = findTierOneRow(rows, rng);
        if (index >= 0) {
            rows.remove(index);
            revisionCountsFor(firm)[2]++;
        }
    }

    /**
     * Picks a row carrying a native transaction id, skipping planted fixtures.
     *
     * <p>Fixtures are excluded because their expected outcomes are stated exactly in
     * {@code fixtures.json}; mutating one would make the revision manifest and the fixture
     * manifest disagree about the same vendor, and the resulting test failure would point
     * at the wrong thing.
     */
    private int findTierOneRow(List<RowDestination> rows, Xoshiro256StarStar rng) {
        for (int attempt = 0; attempt < 24; attempt++) {
            int index = rng.nextInt(rows.size());
            RowDestination candidate = rows.get(index);
            if (candidate.row() != null
                    && candidate.defect() == null
                    && !candidate.row().sourceTxnId().isEmpty()
                    && !candidate.row().sourceTxnId().startsWith("H:")
                    && !candidate.row().vendorTin().startsWith(FixtureCase.FIXTURE_EIN_PREFIX + "-")) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Builds one ordinary payment and, with small probability, corrupts it.
     *
     * <p>Defect injection happens <em>last</em>, on an otherwise valid row, so a defective
     * row differs from a clean one in exactly one respect. That is what keeps the expected
     * rejection reason unambiguous when the test asserts on the report.
     */
    private RowDestination emitPayment(GeneratedVendor vendor,
                                       GeneratedClient client,
                                       String primarySystem,
                                       int txnCounter,
                                       Xoshiro256StarStar rng,
                                       Xoshiro256StarStar defectRng) {

        String system = client.switchedSystems() && rng.nextBoolean(0.4)
                ? client.sourceSystems().get(1)
                : primarySystem;

        PaymentRow row = ordinaryPayment(vendor, client, system, txnCounter, rng);

        if (!defectRng.nextBoolean(SeedConfig.DEFECT_RATE)) {
            return new RowDestination(system, row, null, null);
        }

        DefectClass defect = defectRng.pick(DefectInjector.INJECTABLE);
        DefectInjector.Injected injected = DefectInjector.inject(defect, row, defectRng, "C-999999");

        return injected.isRaw()
                ? new RowDestination(system, null, injected.rawLine(), defect)
                : new RowDestination(system, injected.row(), null, defect);
    }

    private PaymentRow ordinaryPayment(GeneratedVendor vendor, GeneratedClient client,
                                       String system, int txnCounter, Xoshiro256StarStar rng) {

        String name = vendor.spellings().size() == 1
                ? vendor.primaryName()
                : rng.pick(vendor.spellings());

        // Most payments are modest; a long tail runs into the thousands. Log-normal again,
        // because a flat distribution would cluster every vendor at the same total and the
        // threshold would stop discriminating.
        long amountCents = rng.nextLogNormal(Math.log(45_000), 1.1, 500, 5_000_000);

        PaymentMethod method = SeedConfig.PAYMENT_METHOD_ORDER[
                rng.pickWeighted(SeedConfig.PAYMENT_METHOD_CUMULATIVE)];

        LocalDate date = paymentDate(rng);

        EntryType entryType = EntryType.PAYMENT;
        String reverses = null;
        String memo = Corpora.MEMO_TEMPLATES.get(rng.nextInt(Corpora.MEMO_TEMPLATES.size()))
                .replace("%d", Integer.toString(rng.nextInt(1000, 99_999)));

        if (rng.nextBoolean(SeedConfig.REVERSAL_RATE)) {
            entryType = rng.nextBoolean(0.6) ? EntryType.REVERSAL : EntryType.REFUND;
            amountCents = -amountCents;
            memo = rng.pick(Corpora.REVERSAL_MEMOS.toArray(new String[0]));
            if (rng.nextBoolean(SeedConfig.REVERSAL_IN_DECEMBER_RATE)) {
                date = LocalDate.of(config.taxYear(), 12, rng.nextInt(1, 32));
            }
        }

        long withholding = 0;
        if (vendor.withholding() && amountCents > 0 && !method.isCardOrTpso()) {
            withholding = Math.round(amountCents * SeedConfig.BACKUP_WITHHOLDING_RATE);
        }

        ExpenseClass expenseClass = rng.nextBoolean(SeedConfig.NON_SERVICES_RATE)
                ? rng.pick(new ExpenseClass[]{ExpenseClass.MERCHANDISE, ExpenseClass.RENT,
                                              ExpenseClass.REIMBURSEMENT, ExpenseClass.OTHER})
                : ExpenseClass.SERVICES;

        String txnId = switch (system) {
            case "QUICKBOOKS" -> "QB-" + txnCounter;
            case "XERO" -> "xer-%08x".formatted(txnCounter * 2_654_435_761L & 0xFFFFFFFFL);
            default -> "";   // spreadsheets have no transaction id; tier-2 identity handles them
        };

        return PaymentRow.of(system, txnId, client.clientRef(), name,
                             vendor.tinAsWritten(), vendor.tinType(),
                             date, amountCents, method, entryType, reverses,
                             withholding, expenseClass, memo);
    }

    /**
     * Mostly in-year, with a deliberate ~6% falling in the adjacent years.
     *
     * <p>Out-of-year rows are not noise. They prove the determination pass filters by year
     * rather than summing everything it can see, and &mdash; more subtly &mdash; that
     * excluded payments still <em>appear</em> in the vendor's explanation marked
     * {@code EXCLUDED_OUT_OF_TAX_YEAR}, rather than silently vanishing from it.
     */
    private LocalDate paymentDate(Xoshiro256StarStar rng) {
        int year = config.taxYear();
        if (rng.nextBoolean(SeedConfig.OUT_OF_YEAR_RATE)) {
            year = rng.nextBoolean(0.5) ? year - 1 : year + 1;
        }
        int dayOfYear = rng.nextInt(1, LocalDate.ofYearDay(year, 1).lengthOfYear() + 1);
        return LocalDate.ofYearDay(year, dayOfYear);
    }

    // =================================================================================
    // Output files
    // =================================================================================

    private void writeClients(Path firmDir, List<GeneratedClient> clients) throws IOException {
        try (DeterministicCsvWriter writer = DeterministicCsvWriter.open(firmDir.resolve("clients.csv"))) {
            writer.writeHeader(GeneratedClient.HEADERS);
            for (GeneratedClient client : clients) {
                writer.writeRow(client.toCsvRow());
            }
        }
    }

    private void writeManifest(Path firmDir, String firm, List<GeneratedClient> clients,
                               Map<String, DeterministicCsvWriter> writers,
                               java.util.SortedSet<Integer> coveredTaxYears) throws IOException {
        List<ExportManifest.FileEntry> files = new ArrayList<>();
        for (Map.Entry<String, DeterministicCsvWriter> entry : writers.entrySet()) {
            DeterministicCsvWriter writer = entry.getValue();
            files.add(new ExportManifest.FileEntry(
                    writer.path().getFileName().toString(),
                    entry.getKey(),
                    writer.rowsWritten() - 1,   // exclude the header
                    writer.sha256()));
        }

        // Declared coverage: every client this export speaks for. The importer tombstones
        // only inside this scope, which is what makes "absent from the file" mean "deleted"
        // for these clients and mean nothing at all for any other.
        List<String> clientRefs = clients.stream().map(GeneratedClient::clientRef).toList();

        ExportManifest manifest = new ExportManifest(
                firm, config.taxYear(), "generated", config.seed(), config.revision(),
                files, clientRefs, List.copyOf(coveredTaxYears));

        json.writeValue(firmDir.resolve("manifest.json").toFile(), manifest);
    }

    /**
     * States, in advance, exactly what a re-import of this revised export must produce.
     *
     * <p>The counts are lower bounds on rows touched, not on rows the importer reads: the
     * whole point is that the other ~99% of the corpus is byte-identical and must produce
     * no writes at all.
     */
    private void writeRevisionManifest() throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("seed", config.seed());
        manifest.put("revision", config.revision());
        manifest.put("taxYear", config.taxYear());
        // Per firm, not summed. Imports run one firm at a time (firm context drives RLS),
        // so a total across both firms is not something any single run can be asserted
        // against -- a test would have to know the split to use it, which defeats the point
        // of stating the expectation up front.
        Map<String, Object> byFirm = new LinkedHashMap<>();
        revisionCounts.forEach((firm, counts) -> byFirm.put(firm, Map.of(
                "expectedInserted", counts[0],
                "expectedUpdated", counts[1],
                "expectedTombstoned", counts[2])));
        manifest.put("byFirm", byFirm);
        manifest.put("note", "Everything not listed here must import as unchanged: "
                           + "zero heap writes, zero dirty marks, zero re-determination.");
        json.writeValue(config.outputDir().resolve("revision-manifest.json").toFile(), manifest);
    }

    private void writeFixtures(List<FixtureManifest.PlantedCase> cases,
                              List<FixtureManifest.PlantedDefect> defects,
                              FixtureManifest.Aggregates aggregates) throws IOException {
        FixtureManifest manifest = new FixtureManifest(
                config.seed(), config.taxYear(), "generated", cases, defects, aggregates);
        json.writeValue(config.outputDir().resolve("fixtures.json").toFile(), manifest);
    }

    // =================================================================================
    // Formatting helpers -- hand-rolled, because String.format is locale-sensitive and
    // would silently produce a different corpus on a machine with different defaults.
    // =================================================================================

    private static String pad(int value) {
        String s = Integer.toString(value);
        return "0".repeat(Math.max(0, 4 - s.length())) + s;
    }

    private static String pad5(int value) {
        String s = Integer.toString(value);
        return "0".repeat(Math.max(0, 5 - s.length())) + s;
    }
}
