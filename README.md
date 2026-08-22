# 1099-NEC Batch Preparation & Filing

A batch 1099-NEC preparation and filing system for CPA firms, built against the brief in
[`BRIEF.md`](BRIEF.md). That file is the assignment and is left untouched; this file is the
project's own README, and [`WRITEUP.md`](WRITEUP.md) answers the brief's six questions.

**[Video walkthrough](https://drive.google.com/file/d/1bm_K_oS8OwKYUyLYybJ08CbUf_iZADyr/view?usp=sharing)** — the running system and the code, in that order: a
full import against its two-minute budget, the same file imported twice to show nothing moves,
determination over the Part 2 cases, a filing run killed mid-batch and resumed, the invariant
suite, and the morning-after page.

**The organising idea.** The brief says the hard part isn't the features, it's making them
correct under failure. So the shape of this system is: *the application layer is best-effort;
the database is the enforcement point.* Anywhere a design could have been a convention plus a
code review, it is a constraint, a policy, or a revoked grant instead. And wherever identity
or delivery is uncertain, the system fails toward a human — never toward silent aggregation
and never toward silent skipping.

---

## Contents

1. [Video walkthrough](https://drive.google.com/file/d/1bm_K_oS8OwKYUyLYybJ08CbUf_iZADyr/view?usp=sharing)
2. [Quick start](#quick-start)
3. [Measured timings](#measured-timings)
4. [Architecture](#architecture)
5. [The CSV schema](#the-csv-schema)
6. [Seeding and importing](#seeding-and-importing)
7. [Determination](#determination)
8. [The filing state model](#the-filing-state-model)
9. [Running a filing run](#running-a-filing-run)
10. [The morning-after page](#the-morning-after-page)
11. [Security](#security)
12. [Configuration](#configuration)
13. [Testing](#testing)
14. [Operations](#operations)
15. [Assumptions](#assumptions)
16. [Known limitations](#known-limitations)

---

## Quick start

**Requirements:** JDK 21, Maven 3.9+, PostgreSQL 16 running locally. No Docker, no
Testcontainers — Postgres runs natively and the test suite uses a second database on the same
server. (A `docker-compose.yml` is not provided; if you'd rather containerise Postgres, any
16.x image works — only the connection URL changes.)

```bash
# 1. Roles and databases. Needs the postgres superuser once, and only once.
psql -U postgres -f db/setup.sql

# 2. Build. Migrations run automatically on first start.
mvn -DskipTests package

# 3. Generate a corpus: ~1M payment rows, 500 clients, 2 firms, ~124 MB.
#    Takes a minute or two and logs progress per file; it is not hung.
java -jar target/readiness-1.0.0.jar seed --out data

# 4. Import, determine, file — for each firm.
java -jar target/readiness-1.0.0.jar import    --firm=northstar --dir=data/firm-northstar
java -jar target/readiness-1.0.0.jar determine --firm=northstar --full
java -jar target/readiness-1.0.0.jar file      --firm=northstar

# 5. The morning-after page.
java -jar target/readiness-1.0.0.jar serve      # http://localhost:8080
```

Sign in as `sam@northstar` / `readiness-dev` (preparer) or `dana@northstar` (firm admin).
The other firm is `jordan@harborline` and `priya@harborline`. Signing in as each in turn is
the quickest way to see isolation working: identical URLs, entirely different data, and no
filter applied anywhere in the application code.

`db/setup.sql` takes `-v owner_pw=… -v app_pw=…`; without them it uses the documented
development defaults. Everything else is read from the environment
(`OWNER_DB_PASSWORD`, `APP_DB_PASSWORD`, `READINESS_TIN_KEY`, `READINESS_BIDX_KEY`).

---

## Measured timings

Every number below is from a single clean run: schema dropped, rebuilt from migrations,
corpus regenerated, both firms imported and determined from empty. Nothing is a best-of.

**Machine:** Windows 11, 12 cores, 16 GB RAM, PostgreSQL 16.3 local, JDK 21.0.6, with ample
free space on the PostgreSQL data volume. That last clause is load-bearing &mdash; see
[the note below](#disk-headroom-changes-these-numbers-by-2).

| Phase | Budget | northstar | harborline |
|---|---|---|---|
| Seed (999,757 rows, both firms, 124 MB) | — | **4.9 s** (205k rows/s) | |
| **Import** (~500k rows each) | 120 s | **61.7 s** | **51.8 s** |
| Re-import, unchanged (idempotency proof) | — | 22.8 s | 32.4 s |
| **Determination**, full scan | 60 s | **23.0 s** | **17.9 s** |
| Revision import (rev-1 export) | 120 s | 44.6 s | 30.7 s |
| Determination, incremental after revision | 60 s | **2.1 s** (17 of 250 clients) | **2.9 s** (25 of 250) |
| Morning-after page, all three queries | 200 ms | **86–113 ms** (5 warm runs) · `v_client_status` alone 9–22 ms | |

Import detail, northstar: 499,875 rows read · 497,113 inserted · 2,762 rejected across all
eight rejection codes · 750 clients marked dirty. Phase split: parse+COPY 8.4 s, dedupe 3.6 s,
vendor upsert 6.1 s, **merge 39.5 s**, tombstone 4.2 s.

Determination detail, northstar: 497,113 payments classified → 29,977 vendors → **27,505 forms
required**, 3,673 exceptions raised, in 23.0 s. Harborline: 28,700 vendors → 26,781 forms.

### Disk headroom changes these numbers by 2×

A later clean run of the identical pipeline, on the same machine but with the PostgreSQL volume at
**97% full** (11 GB free of 270 GB), missed the import budget outright:

| Phase | Ample headroom | Volume 97% full |
|---|---|---|
| Seed | 4.9 s | 6.8 s |
| Import, northstar | **~55 s** | **137.1 s** — SLA MISSED |
| Import, harborline | ~50 s | 112.3 s |
| Determination, full, northstar | ~16 s | 33.5 s |
| Determination, full, harborline | ~19 s | 36.5 s |

The slowdown is uniform rather than located in one phase &mdash; merge went 39.5 s → 88.8 s, but so
did CPU-bound parsing (8.4 s → 13.6 s) and the tombstone sweep (4.2 s → 16.3 s). Database bloat
was ruled out (the schema was dropped and rebuilt; 724 MB fresh) and so was autovacuum contention
(zero active backends during the run).

Two things are worth taking from this rather than hiding it:

- **`bench` did its job.** It reported `SLA MISSED` and exited non-zero, which is the entire
  reason it exits non-zero rather than printing a number and moving on. A performance claim that
  cannot fail is a decoration.
- **Every correctness result was identical on both runs.** Idempotency 7/7 per firm, revision
  deltas exact, all 16 invariants holding across both firms, both audit chains intact. The
  budgets are the only thing a full disk moves &mdash; which is the right sensitivity for this
  system to have, since a missed SLA is a scheduling problem and a missed invariant is a
  duplicate 1099.

If you reproduce this on a volume near capacity, expect the import figure to roughly double.

**Reproduce it, and let it fail if the claim is false:**

```bash
java -jar target/readiness-1.0.0.jar bench --firm=northstar --dir=data/firm-northstar
echo $?     # non-zero if any stated SLA was missed
```

```
bench  firm=harborline  dir=data-final\firm-harborline  tax-year=2025
       jvm=21.0.6+8-LTS-188  cores=12  heap-max=4,062 MB

phase                                       elapsed     budget   work done
----------------------------------------------------------------------------------------
import                                     29,656 ms  120,000 ms   499,882 read | 0 inserted | 2,744 rejected
                                                                 [PASS]
re-import (idempotent)                     36,151 ms  120,000 ms   499,882 read | 497,138 unchanged
                                                                 [PASS] no deltas, checksum stable, dirty set empty
determine (full)                           17,883 ms   60,000 ms   250 clients | 497,137 payments | 26,781 forms
                                                                 [PASS]
determine (incremental, nothing dirty)         89 ms    2,000 ms   0 clients rescanned
                                                                 [PASS]
dashboard (worst of five)                      68 ms             250 client rows, derived live from views
                                                                 [Info]
----------------------------------------------------------------------------------------
[note] the corpus was already loaded, so the import row measured the no-op merge path
       rather than a first load. Drop the schema and re-run for a first-load figure.
every stated SLA was met
```

That `[note]` is the point of printing the work done next to every time. A benchmark that
reported "import: 29 s" without saying it inserted zero rows would be quoting the no-op merge
path as if it were a first load. `--require-cold` turns the note into a failure, for CI.

`bench` measures import, re-import-with-idempotency-proof, full determination, incremental
determination and the dashboard query, prints the work done next to each time, and **exits
non-zero on a breach**. A benchmark that always succeeds is a report; one that can fail is a
test, and this one belongs in CI. `--require-cold` makes it fail if the corpus was already
loaded, because a silently warm benchmark is worse than no benchmark.

### The import SLA was missed at first, and how it was fixed

The first honest measurement was **152 s against a 120 s budget**. The fix is worth recording
because the dominant cost was not where it looked:

| Phase | Before | After | What changed |
|---|---|---|---|
| parse + COPY | 14.5 s | 9.2 s | — |
| dedupe | 10.9 s | 3.8 s | larger `work_mem` |
| vendor upsert | 8.5 s | 3.8 s | larger `work_mem` |
| **merge** | **103.1 s** | **35.3 s** | 6 indexes → 3; `work_mem` 512 MB; `synchronous_commit=off` |
| tombstone | 14.8 s | 6.7 s | one statement rather than one per source file |
| **total** | **152.2 s** | **59.1 s** | |

The big one was `work_mem`. Measured by disabling the dirty-marking trigger, it accounted for
~22 s of the merge: a statement-level trigger with a transition table makes Postgres
materialise every affected row into a tuplestore — around 100 MB for a 500k-row merge — so at
the default 128 MB it was spilling to disk. Raising the limit kept it in memory and **preserved
the trigger's "cannot be forgotten" guarantee** rather than trading correctness for speed.

---

## Architecture

Java 21 · Spring Boot 3.4 · PostgreSQL 16 · Flyway · picocli · Thymeleaf. **JDBC, not JPA** —
the hot paths are set-based SQL, and an ORM between them and the database would have been a
layer to fight rather than a layer to use.

```
                    ┌──────────────┐
   CSV exports ───► │   ingest     │  COPY → staging → merge → tombstone
                    └──────┬───────┘         (dirty-marking trigger)
                           │
                    ┌──────▼───────┐
                    │ determination│  one set-based pass; reasons written
                    └──────┬───────┘  by the same query as the totals
                           │
                    ┌──────▼───────┐
                    │ transmission │  plan → seal → dispatch → poll
                    └──────┬───────┘  under a 20-call/minute budget
                           │
                    ┌──────▼───────┐
                    │  web (Part 4)│  one REPEATABLE READ snapshot per page
                    └──────────────┘

   Everything above runs inside FirmContext.runAs(firm), and every table is under
   FORCE ROW LEVEL SECURITY. The isolation is not in the boxes; it is underneath them.
```

**Package layout** (`src/main/java/com/soraban/readiness/`):

| Package | What lives there |
|---|---|
| `security` | `FirmContext`, `RlsGuard`, `Tin`, `TinCryptoService`, `FirmUser` |
| `config` | `FirmTransactionManager` — the thing that makes firm context unforgettable |
| `seed` | Deterministic corpus generator (SplitMix64 + xoshiro256\*\*) |
| `ingest` | `ImportPipeline`, dialects, rejection sink, `BookChecksum` |
| `determination` | `DeterminationEngine` (SQL), `PaymentClassifier` (the Java oracle) |
| `transmission` | Planner, dispatcher, poller, reconciler, `InvariantChecker`, rate limiter |
| `transmission/spi` | The IRS interface — no Spring, no JPA, no project imports |
| `transmission/stub` | The stub, backed by its own Postgres schema |
| `audit` | Hash-chained append-only audit log |
| `web` | The morning-after page |
| `cli` | Ten commands |

Two documents carry the reasoning in detail: **[`docs/DECISIONS.md`](docs/DECISIONS.md)** —
92 numbered decisions, each naming the file, the rejected alternative and the reason — and
**[`docs/CRASH_POINTS.md`](docs/CRASH_POINTS.md)**, which enumerates every point a filing run
can die, what is durably true afterwards, and how it recovers.

---

## The CSV schema

An export is a **directory**, not a file:

```
data/firm-northstar/
  manifest.json            firm slug, tax year, per-file sha256, declared client coverage
  clients.csv              client_ref, legal_name, ein, address…
  payments-quickbooks.csv  one file per source dialect
  payments-xero.csv
  payments-spreadsheet.csv
```

**`firm_id` is deliberately absent from every file.** Firm identity comes from the
authenticated context of the import invocation and is stamped server-side. A file can never
assert which firm it belongs to.

| Column | Notes |
|---|---|
| `source_system` | `quickbooks` \| `xero` \| `spreadsheet` |
| `source_txn_id` | Native id where the system has one. Blank for spreadsheets. |
| `client_ref` | The id the bookkeeper uses. Resolved against `app.client`. |
| `vendor_name` | As recorded. Kept verbatim for explainability. |
| `vendor_tin`, `vendor_tin_type` | May be blank or malformed. Neither is a rejection. |
| `payment_date` | Dialect-specific format; the date governs the tax year. |
| `amount` | Signed. Negative for refunds and reversals. |
| `currency` | Non-USD is a rejection. |
| `payment_method` | `check`, `ach`, `credit_card`, `paypal`, `wire`, `cash`… |
| `entry_type` | `PAYMENT` \| `REVERSAL` \| `REFUND` \| `VOID` |
| `reverses_source_txn_id` | So the explanation can say *"reversal of txn X"*. |
| `backup_withholding` | A column, not a synthetic negative line — see below. |
| `expense_category` | Blank is presumed `SERVICES`. |
| `memo` | Free text. Shown on the client page. |

Three of those are choices worth defending:

- **`entry_type` exists** because a negative amount alone cannot distinguish a refund from a
  sign error, and the explanation on the client page should be able to say which.
- **`backup_withholding` is a column, not a line.** A withholding line is not a payment; adding
  it as a negative row would corrupt the year's net and put a phantom transaction in front of
  anyone reconciling against the client's own books.
- **`expense_category` exists** because the rule is "for services". Absent it, every payment
  would have to be presumed reportable, and rent and merchandise would silently inflate Box 1.

### Dialects

A thin `SourceDialect` (YAML-configured, selected by header fingerprint) supplies a header
alias map, ordered date parsers, an amount normaliser and a payment-method synonym map.

Payment methods are matched case-insensitively, with whitespace collapsed and **underscores
folded to spaces**, so `credit_card` and `Credit Card` are the same method typed by two systems.
That fold was missing, and this document is what exposed it: the schema above lists the values in
snake_case, none of them resolved, and a file written exactly as documented had its card payments
treated as non-card. The safe direction — they count toward the threshold, so a form is filed
rather than suppressed — but wrong, and silent. Hyphens are deliberately *not* folded, because
`third-party network` and `p-card` are synonyms in their own right.

**An unrecognised payment method is not a rejection.** It imports, defaults to *non-card*, and
raises an exception for a person. Non-card is the conservative default because it *counts*
toward the threshold: treating an unknown method as card would silently suppress a filing
obligation, and the failure would be invisible.

### Rejections versus exceptions

This is the distinction the brief is probing, so it gets stated plainly.

**Rejections** — the row cannot be represented at all. Skipped individually, reported with
line numbers, TIN-redacted, written on a separate transaction so the report survives a failed
merge:

`UNPARSEABLE_DATE` · `UNPARSEABLE_AMOUNT` · `RAGGED_ROW` · `UNKNOWN_CLIENT_REF` ·
`MISSING_CLIENT_REF` · `SUB_CENT_AMOUNT` · `UNSUPPORTED_CURRENCY` · `UNIDENTIFIABLE_VENDOR`
(name *and* TIN both blank)

**Not rejections** — the row is fine, the *situation* needs a person. These import normally and
become determination exceptions: blank TIN, malformed TIN, unknown payment method, out-of-year
date, negative net for the year.

> *A missing TIN is not a malformed row.* Rejecting it would delete a filing obligation, and
> the vendor would simply vanish from the books rather than appear as work to do.

There is also a **systemic-failure guard**: per-row skipping is right, wholesale skipping is
not. A rejection rate above 5% aborts the run with `TOO_MANY_REJECTIONS`, because a
misdetected dialect quietly importing 3% of a file is far worse than a loud failure. This
caught a real bug — see [Known limitations](#known-limitations).

---

## Seeding and importing

```bash
# Deterministic: same seed, byte-identical output. Different seed, different corpus.
java -jar target/readiness-1.0.0.jar seed --out data --seed 42 --clients 500 --lines 1000000

# A revised export: a scripted diff plus a manifest of expected deltas.
java -jar target/readiness-1.0.0.jar seed --out data-rev1 --revision 1

java -jar target/readiness-1.0.0.jar import --firm=northstar --dir=data/firm-northstar
```

The generator uses hand-rolled SplitMix64 + xoshiro256\*\* with **hierarchical, path-keyed
streams** (`"firm:A/client:0142/payments"`). Streams are independent, so generation
parallelises while staying byte-identical — and, more importantly, **adding a new random draw
later does not shift existing streams.** Without that, every code change reshuffles the corpus
and invalidates every golden test.

The six Part 2 cases are planted in **ordinary clients**, roughly 25 times each across both
firms, with their locations published out-of-band in `fixtures.json`. Nothing in the CSV marks
a row as a fixture. Three cases the brief doesn't list are planted too, because that is where
the identity logic actually lives: TIN backfill (promotion), one name under two TINs, and a
card-mixed vendor *above* threshold.

### Proving a second import changes nothing

```bash
java -jar target/readiness-1.0.0.jar verify-import --firm=northstar --dir=data/firm-northstar
echo $?     # 0 if provably idempotent
```

Actual output from the clean run:

```
before   rows=497,113 deleted=35 vendors=30,397 checksum=b2161cff4e69dabe dirty=0
after    rows=497,113 deleted=35 vendors=30,397 checksum=b2161cff4e69dabe dirty=0
  [PASS] no rows inserted           inserted=0
  [PASS] no rows updated            updated=0
  [PASS] no rows tombstoned         tombstoned=0
  [PASS] live row count unchanged   497113 -> 497113
  [PASS] book checksum unchanged    b2161cff4e69dabe -> b2161cff4e69dabe
  [PASS] vendor count unchanged     30397 -> 30397
  [PASS] dirty set still empty      dirty=0
IDEMPOTENT  (499,875 rows read, 497,113 unchanged, 22,844 ms)
```

Four layers, strongest last. The book checksum is `bit_xor` over every row hash — commutative,
so no 500k-row sort is needed. **The dirty set being empty is the strongest of the four**: it
proves nothing was even *considered* changed, so the downstream cost of a redundant import is
provably zero rather than merely small.

We deliberately do **not** short-circuit on a matching file checksum. Skipping the import
proves nothing; running it and proving the merge was a no-op proves the invariant.

### Row identity across re-imports

One column, one constraint: `unique (firm_id, client_id, source_system, source_txn_id)`.

- **Tier 1 — native id.** Stable across re-exports *and* survives content edits, which is
  exactly what a revised export needs: an amount correction is an **update**, not a
  delete-and-insert.
- **Tier 2 — synthesized.** Spreadsheets have no id, so `'H:' || sha256(natural_tuple ‖
  dup_ordinal)`. **The ordinal matters:** two identical $50 cheques to the same plumber on the
  same day genuinely happen, and a pure content hash silently collapses them into one.

Owned failure mode: reordering byte-identical spreadsheet rows swaps their synthesized ids and
produces a spurious delete-plus-insert. It is provably **determination-neutral** — the natural
tuple covers every field determination reads — so the cost is surrogate-id churn only. It is
documented rather than hidden.

### Re-determination is marked by the database, not by the importer

A **statement-level `AFTER` trigger with transition tables** maintains
`determination_dirty_client`. Chosen over doing it in the importer because it fires once per
statement rather than per row, captures the **pre-image** for free (a date moving 2025→2026
dirties both years), and **cannot be forgotten by any writer** — including a human running an
`UPDATE` in psql at 2 a.m. on 1 February. Dirty marking is a database invariant, not a
convention.

### The revision run

The revised export's manifest declares its expected deltas; the import matched them exactly:

| Firm | Expected inserted / updated / tombstoned | Actual |
|---|---|---|
| northstar | 35 / 22 / 16 | **35 / 22 / 16** |
| harborline | 55 / 35 / 22 | **55 / 35 / 22** |

497,075 rows unchanged, 19 clients marked dirty out of 250, and incremental determination then
rescanned 17 of them in 2.1 s.

---

## Determination

```bash
java -jar target/readiness-1.0.0.jar determine --firm=northstar --full   # rescan everything
java -jar target/readiness-1.0.0.jar determine --firm=northstar          # only what's dirty
```

**Set-based SQL, orchestrated by Java.** Two reasons, and the second is the one that settles it:

1. A Java rules engine has to hydrate a million rows over JDBC — 30–60 s in wire time and GC
   before a single rule runs. One scan plus a hash aggregate takes 15 s.
2. **Explainability.** The per-payment reason is a `CASE` expression *in the same projection*
   that computes the counted amount. The explanation and the total cannot drift, because they
   are the same row of the same query.

Java owns the rule *definitions* — `RuleSet(taxYear, thresholdCents, cardMethods,
nameNormVersion)` — passed as bind parameters, never inlined, and hashed into
`determination_run.ruleset_hash`, so "which rules were in force when we decided this" is
answerable in June.

### Classification order is the rule precedence

```
EXCLUDED_OUT_OF_TAX_YEAR → EXCLUDED_VOID → EXCLUDED_NON_SERVICES
  → EXCLUDED_CARD_TPSO → EXCLUDED_ZERO_AMOUNT → COUNTED_REVERSAL → COUNTED
```

A subtlety falls out for free: **a refund of a card payment carries `payment_method =
credit_card` itself**, so it hits `EXCLUDED_CARD_TPSO` before `COUNTED_REVERSAL` and is
excluded symmetrically with the payment it reverses. An implementation that special-cases
"negative ⇒ counted" first gets this wrong and can drag a vendor's reportable total below any
amount they were actually paid — with no error, no exception, and a plausible-looking number.

### Vendor identity, including the no-TIN problem

Scope is `(firm_id, client_id)`. Identity never crosses clients, because the 1099-NEC is issued
*by* the client; aggregating one TIN across two clients would produce one form for money two
different payers paid.

`ledger_line` groups on **`tin_bidx`**, the HMAC blind index — never on a plaintext TIN.
Grouping and indexing behave identically on a fixed 32-byte `bytea`, and no plaintext TIN ever
enters the million-row table.

1. Normalise → exactly nine digits, or `MALFORMED` plus an exception. We will not make an
   unverifiable string load-bearing for identity, and will not discard it either.
2. Valid TIN → `vendor_key = 'TIN:' || bidx`.
3. No TIN → `'NAME:' || vendor_name_norm`, via a deterministic, **versioned** normaliser.
   **No fuzzy matching.** A false merge files one contractor's income under another's TIN — a
   disclosure incident that looks plausible on screen. A false split produces a visible extra
   form. Trigram similarity is offered as a *suggestion* only, and `vendor_alias` is the human
   escape hatch.
4. **Name → TIN promotion.** If a normalised name maps to **exactly one** distinct valid TIN
   across the year, its no-TIN rows are promoted into that vendor. If **two or more**, it does
   *not* promote — it raises `AMBIGUOUS_VENDOR_IDENTITY` listing the candidates.

The asymmetry in step 4 is the part to defend: **one TIN, many names → merge** (a TIN is a
strong identifier); **one name, many TINs → refuse** (a name is weak; two "Smith Consulting"
entities genuinely exist). Both directions fail toward a person.

Without promotion, a vendor with four blank-TIN rows totalling $520 and one TIN row of $180
splits in two and files **nothing** — a missed $700 obligation, invisible.

### Positions taken

- **Card/TPSO: the threshold basis equals the reported basis.** Box 1 reports only the non-card
  portion. Putting the card portion in Box 1 *and* having the processor report it on a 1099-K
  reports the same income twice under the vendor's TIN — which produces an under-reporting
  notice to the contractor, and that is the error a CPA firm actually gets called about. The
  vendor row stores the full decomposition, so the client page shows the subtraction rather
  than the answer.
- **Backup withholding: the CSV amount is gross of it.** $400 with 24% withheld → Box 1
  $400.00, Box 4 $96.00. The opposite reading changes Box 1, and it is worth being explicit
  that there was a choice.
- **A missing TIN never suppresses the obligation.** `form_required` is computed **first and
  independently**; the missing TIN then attaches a blocking exception and `transmit_blocked`.
  Part 3 still creates a filing in state `BLOCKED` — counted, visible, assignable. A no-TIN
  vendor *below* threshold raises nothing: no obligation means no W-9 to chase, and flagging it
  would bury real blockers under noise.
- **`000`-prefixed TINs are caught pre-flight** rather than transmitted, so a known rejection
  never burns a call from the rate budget.

### Versioned results

`determination_run` (immutable header) · `vendor_determination` (**SCD-2**, only dirty units
rewritten, so storage grows with *change* rather than `runs × vendors`) · `payment_determination`
(current state, overwritten).

The asymmetry is deliberate: **version the decision, materialise the evidence.** A million
payment rows times N runs is unjustifiable and reconstructible anyway. SCD-2 on the vendor
answers "what did we believe on 1 February at 23:00, under which rules" — the question that
matters when a penalty notice arrives.

**The lock:** once transmitted, `locked_by_filing_id` is set. A later run that would change a
locked unit writes a new version and raises `DETERMINATION_CHANGED_AFTER_FILING` rather than
silently disagreeing with what was sent.

### How correctness was established

A ~60-line pure-Java `PaymentClassifier` exists **only as a differential oracle**. A property
test pushes 20,000 generated payments through both it and the production SQL and asserts the
dispositions agree on every one.

The reasoning: tests encode the same understanding the implementation does, so a misreading of
the brief produces a passing test. Two independent implementations that must agree is a
different kind of evidence — for both to be wrong *and* agree, the same mistake has to be made
twice, in two languages, at two levels of abstraction.

Two details make it worth more than it looks:

- The test uses `DeterminationEngine.CLASSIFICATION_CASE_SQL`, **the production constant**, not
  a pasted copy. A copy keeps passing after someone edits the engine, at which point the test
  confirms agreement between two things neither of which is production.
- It asserts **coverage before agreement**. A generator that happened to emit only ordinary
  payments would make agreement trivially true, and "20,000 cases passed" would be misleading.
- The oracle was verified by **mutation**: moving the reversal check ahead of the card check
  made it fail immediately and name the inputs. A property test that has never been seen to
  fail is a decoration.

---

## The filing state model

Two levels of state, deliberately separated. **A receipt is evidence about a batch; an
acknowledgment is evidence about a filing.** Conflating them is how a system ends up unable to
answer "did the IRS get filing #47?"

```java
enum FilingState { DRAFT, READY_TO_TRANSMIT, BLOCKED, BATCHED,
                   SUBMITTED_UNACKNOWLEDGED, ACCEPTED, REJECTED }

enum BatchState  { SEALED, DISPATCHED, SUBMITTED, ACKNOWLEDGED, VOID }
```

```
                      ┌─────────┐
                      │  DRAFT  │
                      └────┬────┘
              preflight    │
         ┌─────────────────┴─────────────────┐
         ▼                                   ▼
  ┌─────────────┐                    ┌───────────────────┐
  │   BLOCKED   │◄── human fixes ───►│ READY_TO_TRANSMIT │
  └─────────────┘      the data      └─────────┬─────────┘
   (counted, visible,                          │  sealed into a batch
    assignable — never                         ▼
    silently dropped)                    ┌───────────┐
                                         │  BATCHED  │
                                         └─────┬─────┘
                                               │  ◄── WRITE-AHEAD BARRIER:
                                               │      this commits BEFORE the HTTP call
                                               ▼
                             ┌──────────────────────────────┐
                             │  SUBMITTED_UNACKNOWLEDGED    │
                             └───────┬──────────────┬───────┘
                        ack ACCEPTED │              │ ack REJECTED
                                     ▼              ▼
                              ┌──────────┐   ┌──────────┐
                              │ ACCEPTED │   │ REJECTED │──► human remediates,
                              └──────────┘   └──────────┘    generation++ , back to
                               (terminal)                    READY_TO_TRANSMIT

   The ONLY path backwards out of SUBMITTED_UNACKNOWLEDGED is proof-carrying:
   a status call that returns "never seen this key" → void the batch, bump generation.
   Not an error. Not a timeout. Not a restart.
```

There is deliberately **no `FAILED`, no `ABANDONED`, no `MAX_RETRIES_EXCEEDED`.**

> `SUBMITTED_UNACKNOWLEDGED` means *"the IRS may have this; we must not send it under a new
> key."* It is a statement about our **epistemic position**, not about what we observed. Under
> failure mode B, "we hold a receipt" and "our request errored and we have no idea" are
> **indistinguishable at the moment they occur** — so they must map to the same filing state.
> Any design that gives them different states has already lost.

### "We stopped retrying" is an attention item, not a state

The brief demands both that every filing be in exactly one state *and* that "we stopped
retrying" be visible to a human. Those pull against each other if attention conditions are
modelled as states — you lose track of where the filing actually is.

So: **state = position in the lifecycle; attention item = why a person is needed.** Orthogonal,
both first-class. `attention_item` carries `unique (entity, type) where resolved_at is null`,
making creation idempotent, and items are auto-resolved in the same transaction that clears the
condition. Attention items never change state and never change polling cadence.

Splitting `SUBMISSION_UNACKNOWLEDGED_TOO_LONG` (we hold a receipt; the IRS is just slow) from
`SUBMISSION_INDETERMINATE_TOO_LONG` (we never got a receipt; mode B may have fired) falls out of
the batch model for free, and Part 4 sorts the second above the first.

### Idempotency

```
filing_id       = uuidv5(NS, firm | client | tax_year | vendor_key)
content_hash    = sha256(canonical_json(filing fields))
idempotency_key = "b1." + base32(sha256("v1" || firm || client || ty ||
                    Σ sorted (filing_id : generation : content_hash)))
```

**UUIDv5 is the whole game.** The most common way to build a "correct" idempotent transmitter
and still ship duplicates is this: determination gets re-run, mints fresh
`bigserial`/`randomUUID()` filing ids, those flow into a brand-new key the server has never
seen, and a filing that is already live gets resubmitted. **Transmission idempotency is only
ever as strong as determination idempotency.**

`generation` is the filing's *attempt epoch*. It increments **only** on positive proof the
previous epoch is dead — a status call returning `Unknown`, or a human remediating a rejection.
Never while a filing might still be live.

**The single most important line of DDL in the project:**

```sql
constraint uq_one_submission_per_epoch unique (firm_id, filing_id, filing_generation)
```

Note it has **no `WHERE` clause**. A partial unique index on a "live" flag would require
maintaining that flag correctly — which is code, which is where bugs live. This says, with no
predicate and no trigger: *a filing may be submitted at most once per epoch, ever.* Every
duplicate-producing bug has to violate this to reach the wire, and it cannot.

### Transaction boundaries

```
T1  PLAN & SEAL        [tx] ──► commit
T2  DISPATCH INTENT    [tx] ──► commit      ◄── the write-ahead barrier
    HTTP POST /submit  [NO tx, NO pooled connection held]
T3  RECORD OUTCOME     [tx] ──► commit
```

T2 flips filings to `SUBMITTED_UNACKNOWLEDGED` *before* the call: **we declare the filings
possibly-live before we make them possibly-live.** The rate token is consumed **inside T2**, so
it rolls back with a failed dispatch and can never be spent without the state change.

`filing_batch` **is** the queue (`next_action_at` plus a lease) — there is no separate outbox
table, so "job state and business state commit together" is trivially true rather than argued.

**No database connection is held across the HTTP call.** That is the classic way a
correct-looking design dies in production: twenty concurrent 30-second calls exhaust the pool.
It is also why T2 and T3 are separate transactions rather than one.

### The freeze rule — where Part 1 meets Part 3

A revised export arrives at 2 a.m. while a batch is in flight. If re-determination mutates
`amount_cents` on a `BATCHED` filing, its `content_hash` changes, the recomputed idempotency
key stops matching, and we have shipped one number and recorded another.

**Sealing freezes content.** Re-determination may only write to `DRAFT`, `READY_TO_TRANSMIT` or
`BLOCKED` filings; anything else becomes a pending amendment plus an
`AMENDED_DATA_FOR_INFLIGHT_FILING` attention item. Enforced by a `BEFORE UPDATE` trigger, not
by discipline.

### Rate limiting

| Approach | Restart-safe | Exactly rolling | Commits with state | Verdict |
|---|---|---|---|---|
| In-memory (Bucket4j) | ✗ restart → instant burst of 20 atop the last 20 | n/a | ✗ | reject |
| Redis token bucket | ✓ | ✗ | ✗ two-phase problem returns | reject |
| Postgres token bucket | ✓ | ✗ | ✓ | reject — see below |
| **Postgres sliding-window log** | ✓ | ✓ | ✓ | **chosen** |

**The token-bucket trap:** a bucket of capacity 20 refilling at 20/60 s permits 20 calls at
t=0 and one more at t=3 s — **21 in the rolling window**. A token bucket whose burst equals its
capacity implements an *average*, not a rolling window. Making it safe requires burst=1 (one
call every 3 s), which is correct but drains a backlog far more slowly than the budget allows.

So: `irs_call_log` plus `pg_advisory_xact_lock` per firm plus
`count(*) where called_at > clock_timestamp() - '60s'`, consumed **inside T2**.

Two details that are real bugs if missed:

- **`clock_timestamp()`, not `now()`.** `now()` is transaction-start time and frozen for the
  transaction, which would backdate calls and silently widen the window.
- **The database clock is the only clock.** Worker JVMs never contribute a timestamp, so
  multi-process clock skew is structurally impossible.

Tokens are never refunded on failure — a failed call consumed real capacity. A crash after T2
commits *under*-uses the budget, which is the safe direction. The lock is never held across the
HTTP call. The log doubles as the compliance audit trail.

### Polling under a scarce budget

Priority is **reconcile > submit > poll**, with a **reserved floor** for polling (at least 4 of
every 20 calls). The submission-over-polling argument is from the domain:

> A filing in `READY_TO_TRANSMIT` at 23:59 on 2 February is a **penalty**. A filing in
> `SUBMITTED_UNACKNOWLEDGED` at 23:59 is **filed on time** — acknowledgment latency is the
> IRS's clock, not ours.

But strict priority alone starves polling entirely: a firm with 50,000 filings would submit for
hours and learn nothing, leaving the morning-after page blank all night and defeating its
purpose. Hence the floor. The whole priority decision is one SQL query, made atomically with
the claim.

Backoff is exponential ×2 with **full jitter**, capped at 15 minutes. Jitter is not optional —
500 batches submitted in the same minute all come due in the same second, the limiter refuses
480 of them, and each reschedules to the same instant: a synchronised retry storm that burns
the budget on refusals.

**Cap the interval, never the count.** The brief says acks take "minutes to hours, occasionally
never", and that the design shouldn't care which. So there is no maximum poll count: a batch
polls every 15 minutes forever until a human resolves it. That is what makes "occasionally
never" a non-event.

Acks correlate by **our own `clientReference`, never by array position.** Positional
correlation corrupts silently the instant a server reorders or coalesces entries, producing
*wrong data in the right shape* — the worst possible failure for a system whose output is a tax
form.

---

## Running a filing run

```bash
java -jar target/readiness-1.0.0.jar file --firm=northstar
java -jar target/readiness-1.0.0.jar file --firm=northstar --max-calls=40   # bounded, for a demo
java -jar target/readiness-1.0.0.jar file --firm=northstar --plan-only      # seal nothing sent
```

The order is not arbitrary: **reconciliation completes before the planner may seal anything.**
A batch whose fate is unknown must be settled before the scarce rate budget is spent on new
work — and a planner running against unreconciled state is the one situation where a filing
that is genuinely live could look eligible again.

Real output from the clean run:

```
reconcile   flagged=0 acted=0 orphans=0
filings     created=27506 ready=24146 blocked=3360 frozen=0 amended=0
batches     sealed=380
transmit    api_calls=20

invariants  ALL HOLD
  [PASS] I1   no filing recorded twice at the IRS for the same generation
  [PASS] I2   nothing is recorded at the IRS that our system never marked as sent
  [PASS] I3   no filing claims an outcome the IRS has no record of
  [PASS] I4   every filing is terminal, scheduled, or flagged for a human
  [PASS] I5   no rolling PT1M window contains more than 20 calls for a firm
  [PASS] I6   batch state and member filing states agree
  [PASS] I7   accepted filings have a record id; rejected filings have a reason
  [PASS] I8   no batch exceeds 100 filings or spans more than one client
```

**Note the 20 API calls.** That is not a limit the command imposed; it is the entire per-minute
budget for the firm, consumed in about 20 seconds. Filing 27,506 forms across 380 batches takes
roughly 19 minutes of pure budget — which is the real capacity ceiling, and the subject of the
"where does this break" answer in the write-up.

### The IRS stub

The interface lives in `transmission/spi` with **no Spring, no JPA and no project entity
imports** — that is what makes "swap in your own implementation" real rather than aspirational.
Errors are modelled by **epistemic class, not HTTP status**:

```java
NotDispatched    // PROVABLY nothing reached the server. The only class permitting "nothing happened".
Indeterminate    // anything after bytes were written.  *** THE DEFAULT. Unknown implies Indeterminate. ***
RejectedRequest  // deterministic 4xx
RateLimited      // 429 — should be unreachable; signals a bug in us
```

A future HTTP client that maps unknown exceptions to the default gets `Indeterminate` and is
**safe by accident**, which is the right direction for a default to fail in.

**Stub state lives in Postgres** (schema `irs_stub`, separate `DataSource`, with an ArchUnit
rule forbidding cross-package reference). Two reasons, the first non-obvious:

1. The kill-and-resume test requires the "IRS" to **outlive the killed worker**. An in-memory
   stub forgets everything on SIGKILL, so every restart would look like a clean slate and mode
   B would be untestable.
2. A separate schema means application code cannot cheat. The seam is real.

**The stub records; the test judges.** `irs_stub.recorded_filing` deliberately has **no
uniqueness constraint** — if the stub enforced no-duplicates, a duplicate bug would surface as
a stub exception rather than as a duplicate, and the test would pass for the wrong reason.

**Mode B is implemented correctly:** record atomically *first*, then roll the failure — persist
the effect, then fail on the response path. Mode A also throws `Indeterminate`, because a real
endpoint could not credibly claim "nothing recorded", so the recovery path is exercised by 12%
of calls rather than 5%.

Every moment a run can die is enumerated in **[`docs/CRASH_POINTS.md`](docs/CRASH_POINTS.md)**
— twelve rows, each with what is durably true, what the endpoint actually holds, and how it
recovers, plus which test covers it. Eight are named points in `CrashHooks.CrashPoint` fired by
production code; the other four are windows between transactions where recovery is "carry on".

**Failure injection is deterministic:** `roll = sha256(seed | idempotencyKey | attemptNo)`.
Failure is a pure function, so a test can arrange exactly *"this batch fails mode B on attempt
1 and succeeds on attempt 2."* A global `Random` gives flaky, un-debuggable tests; this gives a
fixture, and a failing chaos run reproduces from its logged seed.

Every knob is zeroable per the brief: failure rates, latency, ack delay, `ack-never-rate`,
`idempotent-replay`, `enforce-rate-limit`, `hang-on-call-number`.

### Reconciliation

```bash
java -jar target/readiness-1.0.0.jar reconcile --firm=northstar --dry-run   # costs zero API calls
java -jar target/readiness-1.0.0.jar reconcile --firm=northstar
```

On boot, every `DISPATCHED` batch is marked `needs_reconcile` and stale leases are cleared; the
planner is **gated per firm** until reconciliation completes. The default strategy is
`REDISPATCH_SAME_KEY` (one token, and a receipt resolves it immediately); `STATUS_FIRST` is
used when attempts are exhausted or the endpoint is not known to dedupe.

Why the gate matters concretely: **the rate budget.** If new submissions get in first,
ambiguous batches stay ambiguous for minutes while budget is spent on unblocked work — and
Part 4's exception list is wrong the whole time.

The design does **not depend** on server-side deduplication — `STATUS_FIRST` exists so a
non-deduping endpoint is survivable, and `NonDedupingEndpointIT` proves it with
`idempotent-replay=false` and mode B forced on every call.

That claim was **false when it was first written**, and the test is what found it. The setting
was read by the reconciler and by nothing else, so the ordinary retry path always re-sent a
`DISPATCHED` batch under its existing key — `REDISPATCH_SAME_KEY` behaviour hard-wired, whatever
the configuration said. Invisible against a deduplicating endpoint; against a non-deduplicating
one it produced **21 duplicate filings**, one per filing in the run. The worker now reads the
same setting: under `STATUS_FIRST` an ambiguous batch is *asked about*, never re-sent. See D81.

---

## The morning-after page

```bash
java -jar target/readiness-1.0.0.jar serve      # http://localhost:8080
```

Reading order is inverted from the obvious one, and that is the design:

1. **the run banner** — are these numbers still moving?
2. **exceptions** — what needs me?
3. **progress tiles** — how are we doing?
4. **clients** — the full list, worst first

Every dashboard instinct says put the totals first. At 7 a.m. on 1 February that is wrong: the
staff member is not there to admire progress, they are there to find the four things a machine
could not finish, and they should find them before they have read anything reassuring.

### Truthful is the hard part

- **Everything is a `VIEW`, never a stored rollup.** A view cannot go stale, cannot be forgotten
  to refresh, and cannot survive the condition that produced it. If a poll succeeds at 06:59,
  the exception is gone at 07:00 with no reconciliation job in between. A **materialized** view
  would be exactly wrong: stale by construction is the one property this page cannot have.
  Measured cost of deriving it live: **86–113 ms** for the whole page over 27,506 filings,
  against a 200 ms budget. `v_client_status` itself is **9–22 ms** — flat against the 16 ms
  measured on a corpus a third the size, because it aggregates to 250 rows either way. The
  page-level cost is dominated by `v_exception`, not by the client table.
- **Only the human annotation is stored.** `app.exception_ack`, keyed by a stable `dedupe_key`
  rather than a row id, because derived exceptions have no durable identity. The truth is
  derived; the note *about* the truth is persisted. And acknowledging **does not hide the row**
  — an "acknowledged" that also suppressed would let a busy morning quietly empty this page
  with nothing resolved.
- **The whole page renders in one `REPEATABLE READ` transaction.** Five queries in five
  transactions during an active run would be five snapshots of five instants, and the visible
  symptom is a tile saying "fully filed" above a rejection for the same client. Each half true,
  the combination false.
- **`Cache-Control: no-store`**, an "as of HH:MM:SS" from that transaction, a banner while a run
  is active, and a 30-second meta refresh. The refresh is crude on purpose: a fetch-and-diff
  would be nicer and would reintroduce exactly the bug the isolation level removes.
- **Unknowable states render as unknown, never as zero.** `SUBMITTED_UNACKNOWLEDGED` reads
  *"submitted — confirmation not yet returned"*, never "not filed". An optimistic assumption
  rendered as fact is the specific way this page could get someone fined.

### Client status, in priority order — first match wins

| # | Status | Condition |
|---|---|---|
| 1 | **NEEDS ATTENTION** | any `BLOCKED`/`REJECTED` filing, or any open exception |
| 2 | **NOTHING TO FILE** | no filings at all |
| 3 | **FULLY FILED** | every filing accepted |
| 4 | **PARTIALLY FILED** | some accepted, remainder still machine-progressing |
| 5 | **AWAITING IRS** | none accepted, some in flight |
| 6 | **READY TO FILE** | determined and clean, nothing sent |

Needs-attention **dominates** because at 7 a.m. the only question is "what needs me" — a client
showing "partially filed" while holding a rejected form is a lie of omission.

Two statuses beyond the brief's four, both deliberate: **NOTHING TO FILE** (with 500 clients a
large share have no vendor over $600; forcing them into "fully filed" overstates work done, and
into "needs attention" buries real exceptions) and **READY TO FILE** (calling it "awaiting IRS"
would be false — the IRS has nothing).

### Layout

Exceptions are ordered **by risk**, not by count or recency: risk of a duplicate or missed
filing first, then IRS rejections, then paperwork. A reconciliation discrepancy leads because
it is the only entry that can mean money and penalties rather than work, and it is what the
whole Part 3 design exists to surface.

Grouped counts sit above individual rows, because 1,223 individual "vendor missing TIN" rows
would bury the one discrepancy that matters. The individual list is capped at 200 — safe,
because the ordering puts the rare dangerous items above the cut — and **the page says when it
stopped**. A list that silently truncates is the same failure as a stale rollup: it reads as
complete and is not.

500 client rows render server-side in one pass with a client-side text filter and **no
pagination**: staff want Ctrl-F, and pagination hides the one client they came in for behind a
control they have to discover.

**Client detail pages** show the subtraction rather than the answer — gross, then each exclusion
on its own line, then Box 1 — followed by every individual payment with the reason it did or
did not count. Zero-valued exclusion lines are still shown, because "we checked and excluded
nothing" and "we did not consider this" look identical when the line is hidden.

---

## Security

### Firm isolation is structural

Five rules, no exceptions:

1. Every tenant table carries a non-null `firm_id`, including grandchildren.
2. Every `firm_id` is provably consistent with its parent via a **composite foreign key**
   `(firm_id, parent_id) → parent(firm_id, id)`. RLS answers *"can you see it"*; the composite
   FK answers *"could it ever have been wrong."* That is what makes the denormalised `firm_id`
   an invariant rather than a performance hack — a ledger line physically cannot claim firm 1
   while pointing at a client of firm 2, so the policy predicate cannot be fooled by a bad
   write.
3. Every unique constraint is prefixed with `firm_id`. A global unique index leaks cross-firm
   existence through constraint-violation messages, which RLS does not filter.
4. The runtime role is **not the owner and not a superuser**, and every table is
   `FORCE ROW LEVEL SECURITY`.
5. Firm context is only ever set transaction-locally, and never derived from user input.

```sql
create policy firm_isolation on app.ledger_line for all to readiness_app
  using      (firm_id = app.current_firm_id())
  with check (firm_id = app.current_firm_id());
```

`firm_id` columns default to `app.current_firm_id()`, so application code never writes one.
Combined with `WITH CHECK`, a developer literally cannot insert into the wrong firm.

`app.current_firm_id()` is **`STABLE`** — evaluated once per statement, so it works as an index
scan key; `VOLATILE` would force per-row evaluation and defeat the index — and it **raises
`28000` rather than returning NULL**. The NULL variant fails silently closed: safe, but it
produces a maddening "the dashboard is empty and nothing is logged" failure. Raising fails
loudly closed: identical safety, far better diagnosability.

**The role split — the thing that quietly breaks RLS:**

| Role | Superuser | BYPASSRLS | Owns tables | Used by |
|---|---|---|---|---|
| `postgres` | yes | yes | no | one-time bootstrap only |
| `readiness_owner` | no | no | **yes** | Flyway migrations only |
| `readiness_app` | no | no | no | app, CLI, **and the test suite** |

The test suite connects as `readiness_app` deliberately. A suite running as a superuser bypasses
RLS entirely and passes identically against a completely unprotected database.

`FORCE ROW LEVEL SECURITY` is the line people omit. Without it the table owner is exempt from
its own policies and the whole design silently evaporates the moment anything runs as the
owner. It also had a useful side effect here: it made a `SECURITY DEFINER` login-lookup
function *impossible*, which forced a better design (see below).

**`TRUNCATE` is explicitly revoked.** `TRUNCATE` is not filtered by RLS, so a role holding it
can destroy both firms' data in one statement regardless of any policy. So are `REFERENCES`
(FK checks run as the table owner, without RLS — an existence-disclosure channel) and `TRIGGER`
(it would let the app role attach code that runs with owner privileges).

`RlsGuard` re-checks all of it on **every boot** — current user is not a superuser, not
`rolbypassrls`, not a member of the owner role; every `firm_id`-bearing table has
`relrowsecurity AND relforcerowsecurity` and at least one policy; no `TRUNCATE` privilege — and
refuses to start otherwise. This catches "someone granted BYPASSRLS for a debugging session and
left it".

### Firm context under a connection pool

The hazard: a pooled connection is a long-lived server session, and a session-level `SET`
outlives the request that issued it — a leak *caused by* the isolation mechanism.

**Rule: session-level `SET` is banned. Only
`select set_config('app.current_firm_id', ?, true)`.** Three properties make it safe:

1. **Reverted automatically at COMMIT or ROLLBACK.** The connection cannot return to the pool
   carrying it. There is no cleanup code to forget, because there is no cleanup code.
2. **Outside a transaction it does nothing** — so an autocommit query has no firm context and
   hits the `28000` exception. Non-transactional access fails closed and loudly.
3. **`set_config` takes a bind parameter.** `SET LOCAL app.current_firm_id = …` is a utility
   statement that cannot be parameterised, forcing string concatenation — SQL injection inside
   the security control itself.

Issued from `FirmTransactionManager.doBegin`, so it is impossible to open a transaction without
firm context (system transactions opt out by a `system:` name prefix). Nesting works for free.

There is deliberately **no `DISCARD ALL`, no reset SQL, no `connectionInitSql`** — adding them
would signal the primary mechanism isn't trusted. Instead `ConnectionPoolIsolationIT` pins the
pool to **one connection** — so the next borrower is guaranteed to be the same physical session —
and asserts the setting is gone after commit and, more importantly, after rollback: a mechanism
that cleaned up only on the happy path would leak on exactly the requests that failed.

`FirmContext` is a plain `ThreadLocal<Long>`, **not inheritable**, so an `@Async` call or a
parallel stream cannot silently inherit or lose it.

**The firm comes from the authenticated principal and nowhere else** — never a URL, form field
or header. A firm id that can be influenced by the request reduces RLS to "we remembered to
validate it", which is the exact class of control this design replaces. The consequence:
`/client/4711` for another firm's client returns "no such client", not a permission error,
because under RLS the row genuinely does not exist for the session. The page cannot leak the
difference, because the information never reached the process.

### TIN protection

Four constraints at once: reversible (the TIN goes to the IRS), equality and `GROUP BY` over a
million rows, display, and validation.

**AES-256-GCM at the application layer + keyed HMAC-SHA-256 blind index + plaintext `last4`.**

- **Application-level, not `pgcrypto`.** `pgp_sym_encrypt(tin, 'key')` puts the key in the
  statement text, where it lands in `pg_stat_activity`, `pg_stat_statements` and
  `log_statement` output — so the database would hold both the ciphertext and, in its logs, the
  key. Encrypting in Java makes a stolen `pg_dump` inert. Full-disk encryption does not help
  here at all: it protects a stolen drive, not a dump or a replica.
- **A blind index, not deterministic encryption.** Both leak equality — that is the price of
  `GROUP BY` — but the HMAC uses a *separate key*, so compromising the grouping key decrypts
  nothing, and it is one-way, so the column is not a decryption oracle.
- **`firm_id` is inside the HMAC message**, so the same contractor at two firms produces
  different blind indexes and a leaked column cannot be correlated across firms.
- **GCM AAD binds `firm_id ‖ client_id ‖ tin_bidx`**, so a ciphertext copied between vendor rows
  fails to decrypt rather than silently mis-attributing a TIN.
- **An honest caveat.** The TIN space is 10⁹, so a leaked blind-index key makes the whole column
  brute-forceable in seconds. Truncating the index to force collisions would blunt that but
  breaks `GROUP BY` correctness by merging distinct vendors. This is stated plainly rather than
  presenting the blind index as strong.

**The most effective control is not cryptographic: don't store the TIN in a million places.**
`ledger_line` stores **no TIN at all** — only `vendor_id` and `vendor_name_raw` for
explainability. Plaintext exists on `vendor` (tens of thousands of rows), encrypted, plus the
transient submission payload.

Decryption happens at exactly **two call sites**: building an IRS payload, and an explicit
audited human reveal. There is deliberately **no JPA `AttributeConverter`** — that makes
decryption ambient and unauditable, whereas an explicit `TinCryptoService.decrypt()` makes
"where does plaintext exist" a greppable question.

**Keeping TINs out of logs.** A `Tin` value object — deliberately *not* a record, since records
generate a field-printing `toString()` — renders as `***-**-6789`. `toString()` is the actual
leak path: string concatenation, `log.info("{}", vendor)`, Jackson defaults, JDBC parameter
tracing, `IllegalArgumentException("bad TIN: " + value)`. Plus `log_parameter_max_length = 0`,
a Logback masking converter as an explicitly-labelled backstop, and a test that crawls every
rendered page for a bare nine-digit sequence.

### Two roles

`PREPARER` and `FIRM_ADMIN`, split along **irreversibility, not seniority**: starting a filing
run, forcing a state transition, changing run configuration and reading the audit log are
admin-only. "Can send a form to the IRS that cannot be unsent" is a meaningfully different
privilege from "can look at what happened last night", and a role model organised around job
titles puts them on the same side of the line.

**Isolation is not a role concern.** Nothing in `SecurityConfig` mentions firms. A `FIRM_ADMIN`
has no more cross-firm reach than a `PREPARER`: none. If tenancy were expressed as authorities,
"admin" would eventually come to mean "sees everything" — which is the failure the whole design
is arranged to make impossible. Authorization answers "may this person do this"; RLS answers
"whose rows are these"; the two never have to agree about anything.

Authentication itself is deliberately minimal, as the brief asks. Form login against
credentials in `app_user`, bcrypt via a `DelegatingPasswordEncoder`, and nothing else: no
account management, no password reset, no MFA, no registration. It exists at all only because
firm identity has to come from somewhere that is not the request.

The login bootstrap is worth one paragraph, because the obvious fix does not work. Log-in must
run *before* a firm context exists, but `app.current_firm_id()` raises rather than returning
NULL. The reflex answer — a `SECURITY DEFINER` lookup function — **fails under `FORCE ROW LEVEL
SECURITY`**, because the owner is subject to its own policies and hits the identical `28000`.
The actual answer needs nothing new: resolve `app.firm` in a system transaction (that one table
has a deliberately open SELECT policy, precisely because resolving a firm is what must precede
having a firm context), then read `app.app_user` inside `FirmContext.runAs` under the ordinary
policy. A firm's staff list stays firm-scoped, and **there is no `SECURITY DEFINER` function
anywhere in the schema.**

### Audit log

```bash
java -jar target/readiness-1.0.0.jar verify-audit --all-firms --tail=10
java -jar target/readiness-1.0.0.jar verify-audit --firm=northstar --pin=audit-heads.txt
```

**Two logs, deliberately not merged.** `filing_state_transition` and `transmission_attempt` are
*domain history*: high-volume, machine-generated, load-bearing for reconciliation.
`app.audit_event` is a *security and compliance record*: low-volume, actor-centric,
append-only, and readable by someone who does not know the filing model.

The rule that keeps it small without losing anything an auditor needs: **machine transitions are
audited at the run or batch level; human actions are audited per action.** A filing run that
transmits 10,000 forms writes one event; a person acknowledging one exception writes one event.
Over a season that stays in the hundreds to low thousands.

Append-only is structural — three independent mechanisms, any one of which failing still leaves
two:

1. The grants are revoked.
2. There is **no `UPDATE` or `DELETE` policy at all.** Under RLS, a command with no permissive
   policy affects zero rows, so even a mistakenly re-granted `DELETE` does nothing. This is the
   one that survives operator error, because it fails safe rather than loudly.
3. A trigger that refuses outright — and this one also stops the **owner**, the single actor who
   could re-grant to themselves and therefore the one the first two cannot cover.

Events are hash-chained per firm and buffered to `beforeCommit`, so the chain-head row lock is
held for microseconds rather than for the length of a filing run. Events raised on a failure
path use `REQUIRES_NEW` so they survive the rollback that produced them — which is exactly when
you want the record. Events buffered in a transaction that rolls back are **discarded**: a false
entry in the one record that exists to be trusted is worse than a missing one, because nothing
downstream contradicts it.

**Honest framing.** A hash chain is tamper-*evident*, not tamper-*proof*. Anyone who can write
both `audit_event` and `audit_chain_head` can recompute the chain and leave no trace. Real
resistance requires the head to live where the application cannot reach it, so `--pin` appends
the verified head to a file outside the database; production would publish it to WORM storage.
`AuditIT` proves the evidence works by actually tampering — editing a row, deleting one from the
middle, truncating the tail — and asserting each is detected. The three are caught by three
*different* signals, which is why all three are tested: an edited row fails its own hash, a
deleted row breaks the following link *and* leaves a sequence gap, and a truncated tail leaves
every remaining link valid and is caught only by the head.

```
firm northstar (id=1)
  events     2
  chain      INTACT
  head       c04692655d631f5d04716bafd685be331fb69ece2bec81e9a420ca9f13620be5
  recent events:
    #2  20 Aug 11:21:36  system:filing-run       FILING_RUN_COMPLETED     FIRM:1
        {"taxYear": 2025, "apiCalls": 3, "elapsedMs": 4645, "invariants": "ALL_HOLD", ...}
    #1  20 Aug 11:21:27  system:determination    DETERMINATION_COMPLETED  DETERMINATION_RUN:6
        {"mode": "INCREMENTAL", "rulesetHash": "5d6067d368200c71", "formsRequired": 653, ...}
  pinned     ./audit-heads.txt
```

Note the `rulesetHash` on the determination event. "Which rules were in force when this decision
was made" is what a penalty notice in June turns into, and a run id alone does not answer it.

---

## Configuration

Everything is in `application.yml`; the entries worth knowing:

| Key | Default | What it controls |
|---|---|---|
| `readiness.tax-year` | `2025` | Filing year |
| `readiness.threshold-cents` | `60000` | "$600 or more" is inclusive; integer cents, so there is no epsilon to argue about |
| `readiness.import.max-rejection-rate` | `0.05` | Above this the run aborts with `TOO_MANY_REJECTIONS` |
| `readiness.tin.active-key-version` | `1` | Version-keyed, so rotation is a background re-encrypt rather than a cutover |
| `irs.rate.limit` / `irs.rate.window` | `20` / `60s` | The budget. `InvariantChecker` reads the same values, so the assertion and the enforcement cannot disagree |
| `irs.submit.max-filings-per-batch` | `100` | |
| `irs.poll.max-interval` | `15m` | Cap the interval, never the count |
| `irs.poll.reserved-share` | `0.20` | Polling floor, so submissions cannot starve status calls |
| `irs.unack-threshold` | `30m` | Surface to a human past this; polling continues regardless |
| `irs.max-attempts` | `5` | Exhausting these raises an attention item, **not** a terminal state |
| `irs.reconcile-strategy` | `REDISPATCH_SAME_KEY` | Or `STATUS_FIRST` for a non-deduping endpoint |
| `irs.stub.failure-mode-a-rate` | `0.07` | Fails before anything is recorded |
| `irs.stub.failure-mode-b-rate` | `0.05` | **Records everything, then returns an error** |
| `irs.stub.ack-never-rate` | `0.0` | Set to 1.0 to prove "occasionally never" is a non-event |
| `irs.stub.idempotent-replay` | `true` | Set false to prove we survive a non-deduping endpoint |
| `irs.stub.hang-on-call-number` | `null` | Test hook for the real-SIGKILL test |

---

## Testing

```bash
mvn verify        # unit tests + integration tests against a real PostgreSQL
```

Needs step 1 of the [quick start](#quick-start) first — `db/setup.sql` creates the roles and the
separate test database the integration tests run against. Nothing else to configure: every
credential and key has a development default.

**111 tests, all passing** — 42 unit, 69 integration. No mocked database anywhere: every integration test runs against real
PostgreSQL as `readiness_app`, the same unprivileged role production uses.

| Suite | What it establishes |
|---|---|
| `FirmIsolationIT` (11) | A literal `select count(*) from app.ledger_line` with **no `WHERE`** returns one firm's rows. A permanently-retained, deliberately-buggy repository method returns empty rather than another firm's data. Pool-reuse leaves context NULL. No money column is a float. |
| `DeterminationCasesIT` (11) | The brief's six cases, plus three it does not list, **driven end to end**: seed a corpus, import it as the CLI would, determine it, and assert against the `fixtures.json` published out of band. Each case is checked field by field on its canonical planting *and* across all 25 plantings. |
| `ClassifierDifferentialIT` (3) | 20,000 generated payments through both the Java oracle and the production SQL, asserting coverage before agreement. |
| `NonDedupingEndpointIT` (2) | `idempotent-replay=false` plus mode B on every call: an endpoint that would happily record the same filing twice, lying each time. **This test found a real duplicate-filing bug** — see below. |
| `NeverAcknowledgedIT` (2) | `ack-never-rate=1.0`. Filings stay in the honest state, an attention item names the wait, and the batch is *still scheduled* — the assertion a give-up-after-N implementation fails. |
| `ConnectionPoolIsolationIT` (3) | Pool pinned to one connection, so "the next borrower" is the same physical session. Context is gone after commit **and after rollback**. |
| `ArchitectureTest` (9) | The boundaries this README claims, enforced: the stub seam, the SPI carrying no framework types, `@Transactional` banned outside the stub, `Tin` never a record, the domain never depending on the page, the seed generator never depending on Spring or JDBC. |
| `KillAndResumeIT` (3) | In-JVM crash at named points, fresh Spring context, reconcile, resume, assert zero duplicates. |
| `EveryCrashPointIT` (6) | The other five crash points, plus **all eight in sequence against one book** — where each scenario's recovered state is the next one's input, which is much closer to a bad night than any single kill. |
| `RowNormalizerTest` (16) | The rejection-versus-exception line, enumerated. A ragged row, an unreadable date and a sub-cent amount are rejections; a blank TIN, a malformed TIN and an unknown payment method are not. **Found a real bug** — see below. |
| `SeedDeterminismTest` (7) | Same seed, byte-identical files; different seed, no file the same. LF endings, no BOM, no comma-decimals, and every manifest checksum matching the bytes on disk. |
| `TinProtectionTest` (10) | No Spring, no database, milliseconds. `toString()` cannot leak through concatenation, formatting, collections or exception messages; the blind index is firm-scoped; a ciphertext moved to another row/client/firm fails to decrypt; the grouping key does not open the ciphertext. |
| `RealProcessKillIT` (1) | Real `destroyForcibly()` on a child JVM mid-call, with mode B forced. |
| `ModeBNoCrashIT` (1) | Mode B at 100%: **141 of 141** live filings correctly marked `SUBMITTED_UNACKNOWLEDGED`, zero leaked. |
| `RetriesExhaustedIT` (1) | "We stopped retrying" is visible, **and not terminal**, and nothing moved terminal. |
| `DashboardIT` (13) | All six statuses, needs-attention outranking progress, no `firm=` in any page, no bare nine-digit sequence, in-flight never renders as unfiled, exceptions vanish when the condition clears, `relkind` proves the views are views. |
| `AuditIT` (9) | Rollback discards buffered events; failure-path events survive; append-only holds against the app role; one firm's log is invisible to another; and **tampering is detected by actually tampering** — editing a row, deleting one from the middle, and truncating the tail are each performed and each caught. |

### The kill-and-resume test, in two tiers

**Tier 1 — in-JVM crash points.** `CrashHooks.reached(CrashPoint)` at eight named points; the
test implementation throws `SimulatedKill extends **Error**` — not `Exception`, so no
`catch (Exception e)` in a retry wrapper can swallow a simulated crash and quietly convert it
into a handled retry. Then the whole Spring context is closed, a fresh one is built against the
same database, and it reconciles and resumes.

Honest about its limit: it proves the transaction boundaries are where I think they are, but
leaves statics, pool state and sockets intact. Hence:

**Tier 2 — real SIGKILL on a real child process.** `ProcessBuilder` plus `destroyForcibly()`:
**no shutdown hook, no graceful drain, no `finally` block.** Correctness must not depend on
cooperative shutdown, because the January failure is a machine losing power, not a `kill -TERM`.
Combined with `hang-on-call-number=3` and `failure-mode-b-rate=1.0` to produce the strictest
reading of "mid-batch": *the IRS has recorded the filings and our process died before learning
anything.*

**No `@Transactional` on crash tests, ever** — Spring's rollback-per-test hides commit
boundaries, and commit boundaries are the entire subject. **No clock mocking** either:
everything is already configuration (`ack-delay: 0ms`, `rate.window: 500ms`), so the real
limiter and the real backoff code run in every test, just scaled.

### The invariant suite is production code

`InvariantChecker` runs after startup reconciliation, at the end of every filing run, and on
demand. The same assertions that make the tests meaningful monitor the running system — a
correctness argument that only holds inside a test fixture is an argument about the fixture.

```bash
java -jar target/readiness-1.0.0.jar verify-invariants --all-firms --verbose
echo $?     # non-zero on a violation
```

**Zero duplicates, judged against the IRS's own books** — not against our beliefs, which would
only prove we are internally consistent.

**No lost filings** — stronger than "the counts match", because a count can match while a filing
sits in a state nobody will act on:

```
∀ f : f.state = ACCEPTED
    ∨ ∃ a live batch with next_action_at scheduled
    ∨ ∃ an unresolved attention item naming f
```

There is no state in which a filing is quietly not progressing. "We stopped retrying" satisfies
the third disjunct — which is precisely why the brief insists it isn't terminal.

**No leaks in either direction**, the pair that catches mode B exactly:

```
{filings ever SUBMITTED_UNACKNOWLEDGED} ⊇ {recorded at the IRS}   -- nothing live we think we never sent
{filings ACCEPTED ∪ REJECTED}           ⊆ {recorded at the IRS}   -- never claim an outcome they never saw
```

---

## Operations

Ten commands. Every one that touches tenant data takes a mandatory `--firm`, and runs its
entire body inside `FirmContext.runAs`. There is deliberately no "run for all firms" mode for
data commands: firm context is what drives RLS, so a command spanning firms would have to
either run without context (impossible — the transaction manager rejects it) or switch context
mid-run (possible, but it would make the isolation story conditional on a loop being written
correctly).

| Command | Exit code | Purpose |
|---|---|---|
| `seed` | 0 | Generate a deterministic corpus. Needs no database. |
| `import` | 0 | Import an export directory. |
| `verify-import` | 0 / 1 | Prove a second import changed nothing. |
| `determine` | 0 | Decide which vendors need a form, with per-payment reasons. |
| `file` | 0 / 1 | Full filing run. Non-zero on a broken invariant. |
| `reconcile` | 0 / 1 | Settle ambiguous batches. `--dry-run` costs zero API calls. |
| `verify-invariants` | 0 / 1 | The invariant suite against any database state. |
| `verify-audit` | 0 / 1 | Recompute the audit chain. `--pin` writes the head outside the database. |
| `bench` | 0 / 1 | The SLA matrix. **Non-zero on a breach.** |
| `serve` | — | The morning-after page, plus the per-firm workers. |

`serve` and `file` drive **the same code path**, so the demo and the production path are never
two different implementations. That matters most for the kill-and-resume demo: what gets killed
is the actual worker.

---

## Assumptions

Recorded rather than guessed at. The full list with reasoning is in
[`docs/DECISIONS.md`](docs/DECISIONS.md); these are the ones that change output.

1. **Vendor identity is scoped per client**, not per firm — the 1099-NEC is issued *by* the
   client, so aggregating one TIN across two clients would produce one form for money two
   different payers paid.
2. **Payment date governs the tax year.** A reversal recorded in January 2026 does not reduce
   the 2025 total; it is excluded as out-of-year and still appears in the explanation.
3. **The threshold basis equals the reported basis** for card/TPSO payments — see
   [Determination](#determination).
4. **CSV `amount` is gross of backup withholding.**
5. **A missing TIN never suppresses the obligation**; it blocks transmission and raises a
   blocking exception.
6. **"$600 or more" is inclusive**, in integer cents.
7. **Filings are transmitted as they become clean**, rather than holding a client's whole set
   until every vendor is resolved. This makes "partially filed" a normal state rather than an
   anomaly — see the write-up, question 6.
8. **The rate budget is per firm.** Whether it is really per TCC, per firm or per source IP
   determines whether horizontal scaling helps at all.

## Known limitations

- **Batch packing is the real ceiling, not row count.** The endpoint allows 100 filings per call
  but all for one client, at 20 calls per 60 seconds per firm. A client with 6 vendors burns a
  whole call, and client count — never row count — is what binds. 500 clients ⇒ ~760 submit
  calls ⇒ **~38 minutes of pure budget.** At 5,000 clients that is ~6.3 hours of submissions
  alone, before any polling or retries, and an overnight window fails at roughly 6,000 clients
  per firm. Full arithmetic in [`WRITEUP.md`](WRITEUP.md) question 5. This is quantified in the write-up.
- **The blind index is brute-forceable if its key leaks** — 10⁹ possible TINs. Stated above
  rather than glossed.
- **The audit chain is tamper-evident, not tamper-proof**, unless the head is pinned outside the
  database.
- **Reordering byte-identical spreadsheet rows** causes surrogate-id churn (provably
  determination-neutral).
- **Federal holidays are not modelled** in the filing deadline — only the weekend roll. A
  holiday calendar that must be maintained is the same failure mode as a stale rollup.
- **The batch lease is hardcoded at two minutes**, and is the only timing in the system that is
  not configuration. A crash mid-acknowledgment therefore pauses that batch for up to two minutes
  before another worker may touch it — correct, bounded and self-healing, but it cannot be scaled
  down in tests the way the rate window and poll backoff can, which is why that path had no test
  until one was written that expires the lease explicitly.
- **`app.firm` has a deliberately relaxed SELECT policy.** Strict scoping there creates a
  bootstrap paradox: resolving a firm is what must happen before a firm context can exist. It is
  the one exemption, it is documented, and `FirmIsolationIT` lists it explicitly so it can never
  be silent.
- **Attention items are counted at client level, not per tax year** — `attention_item` carries no
  tax year of its own. During a two-year season a client with an unresolved 2024 problem also
  shows attention on its 2025 row. That errs toward putting a person in front of something real.
- **No `docker-compose.yml`.** Postgres runs natively; adding a container would be a second way
  to get the environment wrong.
- **The generator emits no `VOID` entries**, so `EXCLUDED_VOID` never appears in a corpus run.
  The disposition exists, is reachable, and is exercised exhaustively by the differential test
  against the Java oracle &mdash; but it has no end-to-end coverage, which is a fixture gap
  rather than a code gap. Worth stating rather than letting a reader infer coverage from the
  enum.
