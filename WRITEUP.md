# Write-up

Answers to the six questions in [`BRIEF.md`](BRIEF.md). The system itself is documented in
[`README.md`](README.md); the 92 numbered design decisions with their rejected
alternatives are in [`docs/DECISIONS.md`](docs/DECISIONS.md).

---

## 1. What did you build, and what did you deliberately leave out?

### Built

All four parts, plus the security work, as a Java 21 / Spring Boot / PostgreSQL 16 system with
ten CLI commands and one web page.

**Part 1.** A deterministic seed generator (999,757 rows, 500 clients, two firms, ~124 MB, 4.9 s)
and a `COPY`-based import pipeline that lands ~500k rows in **62 s and 50 s** against a 120 s
budget. Three source dialects, an eight-code rejection taxonomy, two-tier row identity, and a
statement-level trigger that maintains the re-determination dirty set as a database invariant
rather than as something the importer remembers to do.

**Part 2.** Set-based SQL determination: 497,113 payments → 29,977 vendors → 27,505 forms in
**23.0 s** against a 60 s budget. Per-payment reasons written by the same query as the totals,
so an explanation cannot drift from the number it explains. SCD-2 versioning on the decision.

**Part 3.** Two-level state machine, a write-ahead barrier, UUIDv5-derived idempotency, a
Postgres sliding-window rate limiter, priority-with-a-polling-floor scheduling, restart
reconciliation, and an eight-invariant checker that is production code rather than test code.
The IRS stub keeps its books in its own Postgres schema so it survives a `kill -9`.

**Part 4.** A page derived entirely from views — tens of milliseconds over 27,506 filings — rendered
inside one `REPEATABLE READ` transaction so its panels cannot describe different instants.

**Security.** Row-level security with `FORCE` on every tenant table, a three-role split, a
transaction-manager-enforced firm context, composite foreign keys that make the denormalised
`firm_id` an invariant, application-layer AES-256-GCM for TINs with an HMAC blind index, and an
append-only hash-chained audit log.

**Verification.** 111 tests (42 unit, 69 integration) against real PostgreSQL as the unprivileged runtime role. `bench`,
`verify-import`, `verify-invariants` and `verify-audit` all exit non-zero on failure, so every
claim in the README is checkable with `echo $?` rather than taken on trust.

### Deliberately left out

**Corrections** — out of scope by instruction, answered in question 4 below.

**Authentication beyond the minimum.** The brief says stub or omit it. There is a form login
with bcrypt credentials in `app_user`, and nothing else: no registration, no password reset, no
MFA, no account management, no session store beyond the servlet default. It exists at all only
because firm identity has to come from somewhere that is not the request — the moment a
`?firm=` parameter exists, row-level security degrades to "we remembered to validate it". I
wrote that parameter first and removed it, which is recorded as decision D66 rather than
quietly fixed.

**Notifications, deployment, containers, any UI beyond Part 4** — out of scope by instruction.
There is deliberately no `docker-compose.yml`: Postgres runs natively, and adding a container
would be a second way to get the environment wrong.

**A real HTTP client.** The SPI is the seam, with no Spring, no JPA and no project imports, so
swapping one in is a real possibility rather than an aspiration. But the error taxonomy is
modelled by epistemic class rather than by status code, and getting that mapping right for a
specific endpoint is work that cannot be done without the endpoint.

**Fuzzy vendor matching.** Trigram similarity is computed and offered as a *suggestion*, never
applied. A false merge files one contractor's income under another's TIN — a disclosure
incident that looks entirely plausible on screen. `vendor_alias` is the human escape hatch.

**Federal holidays** in the filing-deadline calculation. Only the weekend roll is modelled. A
holiday calendar that must be maintained is the same failure mode as a stale rollup: correct
for one season, silently wrong afterwards.

**Cross-firm anything.** No "run for all firms" mode for data commands, no cross-firm report, no
admin view over both. `verify-invariants --all-firms` loops, running each firm in its own
context, which is the honest shape.

**Per-filing audit rows.** Machine work is audited at the run level; only human actions get an
event each. Ten thousand rows saying "a machine transmitted a form" would bury the one row
saying "a person forced a transition", and the per-filing history already exists in
`filing_state_transition` and `transmission_attempt`.

---

## 2. How each Part 2 case flows through the implementation

The cases are planted in **ordinary clients**, roughly 25 times each across both firms, with
locations published out-of-band in `fixtures.json`. Nothing in the CSV marks a row as a fixture,
so the pipeline cannot treat them specially even by accident.

`DeterminationCasesIT` drives all of this **end to end** &mdash; it seeds a corpus, imports it
exactly as the CLI would, runs determination, and asserts against the manifest. Each case is
checked field by field on its canonical planting, then across all 25 plantings: one passing
instance can be a coincidence of that client's other data; twenty-five spread over two firms
cannot. All eleven tests pass.

### Case 1 — one vendor, three spellings, one TIN

**Import:** three rows, three different `vendor_name_raw`, same TIN. The TIN is normalised to
nine digits, HMAC'd into `tin_bidx`, and the *blind index* — never the plaintext — is written to
`ledger_line`.

**Determination:** `vendor_key = 'TIN:' || bidx`. All three rows land in one group because the
grouping key is derived from the TIN and is byte-identical across the three spellings. The
spellings survive on the ledger rows, so the client page still shows what was actually recorded.

`identity_source = DIRECT_TIN`. The name never enters the identity decision at all when a valid
TIN is present, which is what makes this case fall out rather than needing handling.

### Case 2 — December payment reversed, gross $800, net $250

**Import:** the reversal carries `entry_type = REVERSAL`, a negative `amount`, and
`reverses_source_txn_id`. It is a first-class row, not a sign convention — a negative amount
alone cannot distinguish a refund from a data-entry error, and the explanation needs to be able
to say *"reversal of txn X"*.

**Determination:** the reversal classifies as `COUNTED_REVERSAL` and contributes its negative
amount. `gross_cents = 80000`, `reversal_cents = -55000`, `reportable_cents = 25000`.

$250 < $600, so **`form_required = false`**, `requirement_reason = BELOW_THRESHOLD`.

The vendor still appears on the client page with the full decomposition. A vendor that simply
vanished below the threshold would be indistinguishable from one that was never imported, and
"no form required" is a decision this system made and has to be able to defend.

*The subtle part:* had that payment been made by card, the refund would carry
`payment_method = credit_card` too, and would hit `EXCLUDED_CARD_TPSO` **before**
`COUNTED_REVERSAL` — excluded symmetrically with the payment it reverses. An implementation
that checks "is the amount negative?" first counts the refund while excluding the payment,
dragging the reportable total below any amount the vendor was actually paid, with no error and
a plausible-looking number. That ordering is pinned by its own test.

### Case 3 — exactly $600.00

`reportable_cents = 60000`, threshold `60000`, comparison `>=`. **`form_required = true`**,
`requirement_reason = THRESHOLD_MET`.

Money is `bigint` cents everywhere, so there is no epsilon to argue about. A migration test
greps `information_schema.columns` for float types and **fails the build** if one appears — the
boundary is exact because the representation cannot be inexact.

### Case 4 — vendor with no TIN

This is the one the brief is really testing, and it has three sub-cases.

**4a — no TIN anywhere.** `tin_status = MISSING`, so `vendor_key = 'NAME:' || vendor_name_norm`
via a deterministic, versioned normaliser. The vendor is resolved, aggregated, and — if over
threshold — gets **`form_required = true`** computed **first and independently**. Only then does
the missing TIN attach a `BLOCKING` exception and set `transmit_blocked`.

The ordering is the whole point. Part 3 still creates a filing record, in state `BLOCKED`:
counted, visible on the dashboard, assignable to a person. The test asserts the vendor *exists*
with `form_required = true`; **absence is the failure mode**, because a silently skipped vendor
is a missed filing obligation that nobody will ever notice.

A no-TIN vendor *below* threshold raises nothing. No obligation means no W-9 to chase, and
flagging it would bury real blockers under noise.

**4b — TIN backfilled later (promotion).** A vendor with four blank-TIN rows totalling $520 and
one TIN-bearing row of $180. Without promotion these are two vendors, neither over $600, and the
system files **nothing** — a missed $700 obligation, invisible.

So: if a normalised name maps to **exactly one** distinct valid TIN across the year, its no-TIN
rows are promoted into that vendor. `identity_source = NAME_TIN_PROMOTION`, recorded so the
merge is explainable rather than magic.

**4c — one name, two TINs.** Promotion **refuses** and raises `AMBIGUOUS_VENDOR_IDENTITY`
listing the candidates.

The asymmetry between 4b and 4c is the part worth defending: **one TIN, many names → merge**,
because a TIN is a strong identifier. **One name, many TINs → refuse**, because a name is weak
and two "Smith Consulting" entities genuinely exist. Both directions fail toward a human.

### Case 5 — $2,400 paid, $1,900 by credit card

**Import:** `payment_method` is normalised through the dialect's synonym map into `method_canon`
and `is_card_or_tpso`. An *unrecognised* method is not a rejection: it imports, defaults to
**non-card**, and raises an exception. Non-card is the conservative default because it counts
toward the threshold — treating an unknown method as card would silently suppress a filing
obligation.

**Determination:** card rows classify `EXCLUDED_CARD_TPSO`. `gross_cents = 240000`,
`card_excluded_cents = 190000`, `reportable_cents = 50000`. $500 < $600, so
**`form_required = false`**.

**The position taken, and it is a position:** the threshold basis equals the reported basis. Box
1 reports only the non-card portion. Putting the card portion in Box 1 *and* having the
processor report it on a 1099-K reports the same income twice under the vendor's TIN, producing
an under-reporting notice to the contractor — which is the error a CPA firm actually gets
called about.

A fifth-and-a-half case is planted deliberately, because a reviewer will ask: **case 5b**, a
card-mixed vendor whose *non-card* portion is still above threshold. It files, and Box 1 carries
only the non-card portion.

### Case 6 — $400 with backup withholding

**Import:** `backup_withholding` is a **column**, not a synthetic negative line. A withholding
line is not a payment; adding one as a row would corrupt the year's net and put a phantom
transaction in front of anyone reconciling against the client's own books.

**Determination:** `reportable_cents = 40000`, `withholding_cents = 9600`. $400 < $600, but
withholding is present, so **`form_required = true`** with
`requirement_reason = BACKUP_WITHHOLDING`.

**The position taken:** the CSV amount is **gross of** withholding. $400 with 24% withheld →
Box 1 $400.00, Box 4 $96.00. The opposite reading changes Box 1, and it is worth being explicit
that there was a choice rather than presenting one reading as obvious.

Withholding is only counted for in-year, non-card payments, for consistency with the reportable
basis. Withholding on a card payment would be incoherent anyway — the processor is reporting
that payment, so the withholding belongs to their filing.

### Explainability

The client detail page shows the **subtraction, not the answer**: gross, then each exclusion on
its own line, then Box 1 — followed by every individual payment with the reason it did or did
not count, joined to `app.reason_code` so the words come from the database rather than from a
second copy kept in a template.

Zero-valued exclusion lines are still shown. "We checked and excluded nothing for this reason"
and "we did not consider this reason" look identical when the line is hidden, and they mean very
different things.

Nothing on that page is recomputed for display. The dispositions were written by the same query
that produced the totals, so the explanation and the number are two columns of one row — and if
the rules change later, the page shows what was *decided*, not what the current rules *would*
decide. That is the version that matters when a penalty notice arrives.

---

## 3. How did you convince yourself this is correct when it gets interrupted?

Five things, and they are deliberately of different kinds, because they fail differently.

### 3.1 The state model makes the ambiguity explicit rather than resolving it

The central claim is that under failure mode B, "we hold a receipt" and "our request errored and
we have no idea" are **indistinguishable at the moment they occur**. So they map to the same
filing state:

> `SUBMITTED_UNACKNOWLEDGED` means *"the IRS may have this; we must not send it under a new
> key."* It is a statement about our epistemic position, not about what we observed.

Crash point **C6** (we crashed after the IRS recorded) and crash point **C7** (mode B: the IRS
recorded and then lied to us) produce **byte-identical durable state**. My system cannot tell
them apart — **and does not need to**, because the recovery is the same. Collapsing them into
one epistemic state with one recovery procedure *is* the design goal, not a limitation of it.

Twelve crash points (C0–C11) are enumerated in
**[`docs/CRASH_POINTS.md`](docs/CRASH_POINTS.md)** — each mapped to durable state, actual IRS
state, recovery, and the test that covers it. All resolve to "no duplicate possible".

That document did not exist when this sentence was first written, which is the second time in
this project that a confident claim turned out to have nothing behind it. Writing it out also
showed that of the eight crash points the code actually fires, only three had ever been armed by
a test. The other five now are, along with a scenario that runs all eight in sequence against one
book — so each recovery's output is the next crash's input.

### 3.2 One constraint that every duplicate-producing bug must violate

```sql
constraint uq_one_submission_per_epoch unique (firm_id, filing_id, filing_generation)
```

**No `WHERE` clause.** A partial unique index on a "live" flag would require maintaining that
flag correctly, which is code, which is where bugs live. This says, with no predicate and no
trigger: *a filing may be submitted at most once per epoch, ever.*

It only works because `filing_id` is `uuidv5(firm | client | tax_year | vendor_key)` — derived
from the business key, not minted. The most common way to build a "correct" idempotent
transmitter and still ship duplicates is that determination re-runs, mints fresh
`randomUUID()` ids, and those flow into a key the server has never seen. **Transmission
idempotency is only ever as strong as determination idempotency.**

And `generation` increments **only on positive proof the previous epoch is dead** — a status
call returning `Unknown`, or a human remediating a rejection. Never on an error, never on a
timeout, never on a restart.

### 3.3 Two tiers of kill test, because the cheap one has a known blind spot

**Tier 1 — in-JVM crash at eight named points.** `SimulatedKill extends **Error**`, not
`Exception`, so no `catch (Exception e)` in a retry wrapper can swallow a simulated crash and
quietly convert it into a handled retry. Then the Spring context is closed entirely, a fresh one
is built against the same database, and it reconciles and resumes.

I am explicit that this proves the transaction boundaries are where I think they are, and
nothing more: statics, pool state and sockets survive. So:

**Tier 2 — real SIGKILL on a real child process.** `ProcessBuilder` plus `destroyForcibly()`:
no shutdown hook, no graceful drain, no `finally` block. Correctness must not depend on
cooperative shutdown, because the January failure is a machine losing power, not a `kill -TERM`.

Combined with `hang-on-call-number=3` and `failure-mode-b-rate=1.0` to produce the strictest
reading of "mid-batch": *the IRS has recorded the filings and our process died before learning
anything at all.* The stub's state lives in Postgres precisely so the "IRS" outlives the killed
worker — an in-memory stub forgets everything on SIGKILL, every restart looks like a clean
slate, and mode B becomes untestable.

**No `@Transactional` on any crash test.** Spring's rollback-per-test hides commit boundaries,
and commit boundaries are the entire subject. **No clock mocking** either: everything is already
configuration, so the real limiter and the real backoff code run in every test, just scaled.

### 3.4 Invariants judged against the other system's books, and run in production

`InvariantChecker` is production code. The same eight assertions run after startup
reconciliation, at the end of every filing run, and on demand via `verify-invariants`. A
correctness argument that only holds inside a test fixture is an argument about the fixture.

The two that matter most are a **pair**, and they catch mode B from both directions:

```
{filings ever SUBMITTED_UNACKNOWLEDGED} ⊇ {recorded at the IRS}   -- nothing live we think we never sent
{filings ACCEPTED ∪ REJECTED}           ⊆ {recorded at the IRS}   -- never claim an outcome they never saw
```

Both are asked of `irs_stub`'s own tables, not of ours. Checking our own beliefs would only
prove we are internally consistent.

The "no lost filings" invariant is stronger than "the counts match", because a count can match
while a filing sits in a state nobody will act on:

```
∀ f : f.state = ACCEPTED  ∨  ∃ live batch scheduled  ∨  ∃ unresolved attention item naming f
```

There is no state in which a filing is quietly not progressing. "We stopped retrying" satisfies
the third disjunct — which is exactly why the brief insists it is not terminal.

`irs_stub.recorded_filing` deliberately has **no uniqueness constraint**. If the stub enforced
no-duplicates, a duplicate bug would surface as a stub exception rather than as a duplicate, and
the test would pass for the wrong reason.

### 3.5 The evidence that actually changed my mind: the bugs it found

This is the honest part of the answer. The design arguments above are what I believed *before*
running it. What convinced me was that the machinery kept catching things I had not anticipated
— and, more usefully, what it caught was proportionate to the effort:

- **The system secretly depended on the server deduplicating, and produced 21 duplicate
  filings.** `irs.reconcile-strategy` was read by the reconciler and by nothing else, so the
  ordinary retry path always re-sent a `DISPATCHED` batch under its existing key. Against the
  default stub that is invisible, because the default stub deduplicates. Turn that off and every
  retry records the filings again: six SUBMIT calls per key, forty-two recorded filings for
  twenty-one filings.

  This is the worst bug in the project and I found it late, by writing a test for a claim the
  README already made — *"the design does not depend on server-side dedupe, and a test proves
  it."* There was no such test. The switch had a comment in `application.yml` reading *"set false
  to prove we survive a non-deduping endpoint"* that had never once been acted on.

  **A configuration switch no test ever flips documents an intention, not a capability.** The
  fix routes an ambiguous batch to a status call under `STATUS_FIRST` — ask, never guess, which
  is what every other part of the design already says.
- **A duplicate-filing bug.** `!serverMayHaveRecorded()` released a batch back to the pool on
  attempt 2 after a mode-B attempt 1 had already recorded it at the IRS. Caught by the zero-
  duplicates invariant, in a test that only exists because the stub records rather than judges.
  The fix is a one-line guard (`attemptNo == 1`), and the bug is invisible without failure mode
  B being modelled properly.
- **A connection leak on every rejected transaction.** `FirmTransactionManager.doBegin` threw
  *after* `super.doBegin()` had already bound a pooled connection. Every transaction opened
  without firm context leaked one. Found because RLS makes context-free transactions fail
  loudly, so the path was exercised constantly instead of never.
- **Seven inert `@Transactional` annotations.** Self-invocation means the proxy is bypassed and
  the annotation does nothing. In an ordinary system these would have kept working by accident,
  because each statement would just commit independently. Here, no transaction means no
  `set_config`, which means `app.current_firm_id()` raises `28000` immediately. **The isolation
  design caught seven latent transaction bugs it was never built to catch.**
- **`AFTER_SEAL_COMMIT` firing inside the transaction**, so a crash at that point rolled the
  seal back — the exact scenario the crash point existed to test, tested wrongly.
- **An invariant asserting the wrong thing.** I5 hardcoded "20 calls per 60 seconds" while the
  test profile configures a 500 ms window, so it reported violations that were not violations.
  Now parameterised from the same properties the limiter reads, so the assertion and the
  enforcement cannot disagree.
- **An invariant with a false positive that only appears with two firms.** I2 joined the
  RLS-scoped `app.filing` to the deliberately unscoped `irs_stub.recorded_filing`. The first
  time both firms transmitted in the same database, firm 2's check saw firm 1's 692 records,
  found no matching firm-2 filing, and reported every one as a leaked filing. The fix is a join
  through `irs_stub.submission.firm_id`; the lesson generalises — **whenever an RLS-scoped table
  is joined to an unscoped one, the scoping has to be restated by hand**, because the mechanism
  that usually does it silently stops at the schema boundary.
- **The documented CSV schema did not import correctly.** `README` lists payment
  methods in snake_case — `credit_card`, `paypal` — and the parser normalised case and
  whitespace but not underscores, so none of them resolved. A file written exactly as
  documented had its card payments treated as non-card and counted toward the $600 threshold.
  The safe direction, and still wrong. Found by writing the first test of the rejection
  taxonomy; the fix folds underscores to spaces and deliberately leaves hyphens alone, since
  `third-party network` is a synonym in its own right.
- **`seed` was broken and nothing noticed.** The stub's beans gate on `irs.client`, not on the
  database switch, so a command that touches no database still tried to build a `JdbcTemplate`.
  Found by running the full pipeline from a dropped schema rather than from the state left by
  the last run.
- **The client page hid every out-of-year payment.** `payment_determination` files each row under
  the *ledger line's* year, and the page filtered on the year being determined. The symptom was
  an explanation that did not reconcile: the vendor's subtraction said *"less payments dated
  outside the tax year: −$430.00"* and then listed nothing accounting for the $430. The engine
  was right; only the page was wrong. Found by writing the test for the six cases &mdash; the
  test I had already claimed existed.
- **Four of my own claims were not backed by anything.** The write-up said each Part 2 case had
  a focused test; there was no such test. The README said an ArchUnit rule sealed the stub seam
  — the dependency was in `pom.xml` and the rule existed only in prose. It also claimed a
  pool-reuse test pinned the connection to size 1, and a test proving survival against a
  non-deduplicating endpoint. Neither existed.

  All four are now real, and writing them found four defects: the duplicate-filing bug above,
  the client page hiding out-of-year payments, eight `@Transactional` annotations contradicting
  a decision I had recorded as done, and three test-isolation bugs.

  A third pass found three more: no crash-point enumeration existed despite being cited by
  number, five of the eight crash points the code fires had never been armed, and the TIN
  protections — the most sensitive thing in the schema — had no unit test at all.

  The pattern is worth stating plainly, because it is the most useful thing I learned here:
  **the claims I was most confident about were the ones with no test behind them.** Auditing
  prose against `find src/test` turned out to be the highest-yield review I did, three times
  running, and it found a duplicate-filing bug that no amount of re-reading the code would
  have.

For determination specifically, the argument is different in kind: **two independent
implementations that must agree.** A ~60-line pure-Java classifier and a SQL `CASE` expression,
20,000 generated payments pushed through both. For both to be wrong *and* agree, the same
mistake has to be made twice, in two languages, at two levels of abstraction. The test uses the
production SQL constant rather than a copy, asserts coverage before agreement, and was verified
by **mutation** — moving the reversal check ahead of the card check makes it fail immediately
and name the offending inputs. A property test that has never been seen to fail is a decoration.

---

## 4. How would you model corrections?

**A filed 1099 is an event, not a record you edit.** Any model that treats a correction as an
`UPDATE` is wrong before it starts, because the original was transmitted, the vendor has a copy,
and the IRS has a copy. What exists afterwards is *two* facts, not one revised fact.

### The IRS's own model, which the data model should follow

- **Type 1** — wrong money amount, or a form filed that should not have been. One corrected
  return, marked CORRECTED, carrying the right figures.
- **Type 2** — wrong TIN or wrong name. **Two** returns: one that voids the original by
  reporting it at zero with the original identifiers, and one that files correctly with the new
  ones.

A single `corrected_amount` column cannot express Type 2 at all. That alone rules out the
edit-in-place model.

### Schema

```
filing                 immutable once ACCEPTED. Never updated. Never deleted.

filing_version         every artifact ever transmitted for this filing, one row each:
                         (filing_id, version, kind, content_hash, irs_record_id, state)
                       kind ∈ { ORIGINAL, CORRECTION_TYPE_1, VOID_LEG, REPLACEMENT_LEG }
                       A Type 2 correction is TWO rows sharing one correction_request_id.

correction_request     the WORKFLOW object, not the data change:
                         requested_by, reason_code, narrative,
                         approved_by, approved_at,
                         state ∈ { DRAFT, SUBMITTED_FOR_REVIEW, APPROVED, QUEUED,
                                   TRANSMITTED, ACCEPTED, REJECTED, WITHDRAWN }
                       Carries the before/after so the diff is reviewable without
                       reconstructing it from two versions.
```

`correction_request` is a first-class workflow object with an approval step because **a
correction is a decision with liability attached, not an edit.** Someone is asserting that a
figure sent to the federal government was wrong; that is a different act from fixing a typo, and
the record should show who decided it and on what basis.

### Why this is the test of whether Part 3 was designed correctly

A correction **reuses the transmission machinery unchanged**. A `filing_version` is just another
thing with a `content_hash` and a `generation`; it batches, seals, dispatches behind the same
write-ahead barrier, and polls through the same acknowledgment path. The idempotency key
derivation already includes the generation, so a correction cannot collide with the original.

If corrections had required a parallel transmission path, that would have been evidence the
original abstraction was wrong. They do not — and *that* is the useful result of thinking this
through, rather than the schema itself.

### Preventing two people correcting the same filing at once

Three layers, and it matters which one is load-bearing.

**Optimistic locking (`@Version` / `lock_version`) is the UX mechanism.** It gives the second
person a clean "someone changed this while you were editing" instead of a silent overwrite. It
is not the guarantee.

**The actual guarantee is a partial unique index:**

```sql
create unique index one_open_correction_per_filing
  on app.correction_request (firm_id, filing_id)
  where state in ('DRAFT', 'SUBMITTED_FOR_REVIEW', 'APPROVED', 'QUEUED');
```

Because the race that matters is **two people *creating* requests concurrently**, and that is a
check-then-act race that no application code closes at `READ COMMITTED`. Both transactions run
`select … where state in (…)`, both see nothing, both insert. Optimistic locking does not help:
there is no existing row whose version could conflict. Only a uniqueness constraint evaluated by
the database can refuse the second insert.

The `WHERE` clause is what lets a filing accumulate a history of closed corrections while
allowing at most one open at a time — the thing a plain unique constraint could not express.

**An advisory claim lease** (`pg_advisory_lock` on the filing, with a TTL) is UX only: it lets
the UI say "Priya is editing this" rather than letting two people do the work and discarding
one. It is explicitly *not* a correctness mechanism, because a lease can expire mid-edit.

**`SELECT … FOR UPDATE` in the short approve-and-queue transaction** is the final serialisation
point: approval reads the filing row `FOR UPDATE`, re-checks that the filing is still `ACCEPTED`
and that no other version is in flight, and queues in the same transaction. That window is
milliseconds, so it does not need a lease.

### Two things that would trip this up in practice

**A correction arriving while the original is still `SUBMITTED_UNACKNOWLEDGED`.** There is
nothing to correct yet — we do not know whether the IRS has it. The correct behaviour is to
refuse and raise an attention item, not to queue one speculatively. This is the same freeze rule
that already governs re-determination against in-flight filings, which is a good sign the
concept generalises.

**A revised export that drops a vendor below $600 after the form was accepted.** Type 1 with
zero? A void? Nothing at all? That is a policy question, not an engineering one, and it is in
question 6 below.

---

## 5. Where does this break?

Not at the row count, which is the answer I expected before measuring. Import runs at ~8,000
rows/second and determination at ~32,000 payments/second; both are comfortably linear and both
have an order of magnitude of headroom against their budgets.

### The wall is batch packing against the per-call client restriction

The endpoint allows **100 filings per submit call, but all for the same client**, under **20
calls per 60 seconds per firm**.

That makes the binding quantity **the number of clients, not the number of filings.** A client
with 6 vendors burns an entire call to send 6 forms — 6% of the theoretical 100.

Measured on the real corpus: **250 clients, 27,506 filings, 380 batches, mean batch 63.5 of a
possible 100.** So:

| Firm size | Clients | Submit calls | Pure submit time at 20/min | With polling (≈1.3×) |
|---|---|---|---|---|
| This corpus | 250 | 380 | **19 min** | ~25 min |
| The brief's "500 clients" | 500 | ~760 | **38 min** | ~49 min |
| 5,000 clients | 5,000 | ~7,600 | **6.3 hours** | **~8.2 hours** |
| 20,000 clients | 20,000 | ~30,400 | **25.3 hours** | **~33 hours** |

**A correction worth making explicitly**, because an earlier draft of this answer overstated the
case. I originally wrote that a client with six vendors burns a whole call at 6% utilisation.
That was measured against a corpus whose vendor distribution was wrong — it gave each client far
too few contractors, so batches were nearly empty and the waste looked catastrophic. With a
realistic distribution, mean batch occupancy is **63.5%**, and packing is much better than I
claimed.

**The constraint is real but narrower than I first described.** It is not that batches are mostly
empty; it is that **every client costs at least one call regardless of size**, and the client
dimension is the only one the protocol lets you batch along. A firm of 5,000 one-vendor clients
needs 5,000 calls to send 5,000 forms — 4.2 hours to file what would fit in 50 calls if mixing
were allowed.

So the sensitivity is to **client count and client shape**, never to row count. Five thousand
clients averaging 6 vendors and five thousand averaging 110 both need thousands of calls; the
first wastes 94% of each batch, the second wastes 37%.

Observed directly: `file --firm=northstar` consumed its **entire 20-call minute in about 20
seconds** and then had nothing to do. The system is idle roughly two-thirds of the time and
cannot use the capacity.

**At around 6,000 clients per firm this stops fitting in an overnight window**, and the whole
premise of a "morning after" page fails — staff arrive to a run that is a third done.

**The lever is not throughput; it is the per-call client restriction.** Doubling the machine
changes nothing. Doubling the rate limit halves the time. Allowing mixed-client batches would
take 5,000 small clients from ~4 hours to well under an hour — a large improvement from a
protocol change and zero code. That is the conversation to have with whoever owns the endpoint,
and it is why this is question 6's first item.

### Four other failure modes, in order of when they arrive

**Poll amplification, ~2,000+ batches.** Every submitted batch needs at least one status call.
At 380 batches the polling floor (4 of every 20 calls) is ample. At 7,600 batches, polling alone
wants 7,600 calls — 6.3 hours of the same budget the submissions need. The floor stops polling
starving, but at that scale submissions and polls are competing for the same scarce thing and
the total is simply the sum. The design does not break; the deadline does.

**Mode B at scale, ~380 ambiguous batches.** At 5% of 7,600 submit calls, 380 batches end up
`DISPATCHED` with unknown outcomes. Each needs a reconciliation call from an **already-committed**
budget. Recovery is correct, and it costs 19 minutes of a budget that had none spare. Worse: the
planner is gated per firm until reconciliation completes, so a restart at hour 3 of a 4-hour run
stops all new submissions until 380 batches are settled.

**A single payroll-aggregator client with 200,000 vendors.** 2,000 calls, all for one client,
and **it cannot be parallelised across the client dimension** because that is the only dimension
the protocol offers. 100 minutes for one client, during which that firm's other 4,999 clients
are behind it in the same queue. Batch ordering would need to become fairness-aware — round-robin
across clients rather than largest-first — which the current planner does not do.

**Import memory, ~5M rows in one export.** The merge holds a transition-table tuplestore sized
with the changed set. It needed `work_mem = 512 MB` at 500k rows. At 5M rows in a single
statement it would spill and the SLA would be missed again — the fix is chunking the merge by
client range, which is straightforward but is not built.

### What does *not* break, and why that is worth stating

- **Adding firms.** Everything is per-firm: the rate budget, the worker pool, the RLS policy.
  Firm 1's backlog cannot starve firm 2, structurally.
- **Acks that never arrive.** No maximum poll count exists — only a capped interval. A batch
  polls every 15 minutes forever until a person resolves it, so "occasionally never" is a
  non-event by construction.
- **Restarts.** Reconciliation is gated ahead of new work and is idempotent. Five restarts in
  ten seconds is a test, not a hazard.
- **Dashboard size — the client table.** It aggregates to one row per client per year whatever
  the corpus, so it is flat: **9–22 ms** over 27,506 filings against 16 ms over 10,580. Adding
  filings does not move it.

**Where the page does grow, stated precisely.** The whole page is **86–113 ms** across five warm
runs against a 200 ms budget — but the dominant term is `v_exception`, not the client table.
Specifically its anti-join branch, which scans every filing for the firm to find determination
exceptions nobody has raised an attention item for. That branch is linear in filings, so at
roughly 4× this corpus it would consume the budget by itself.

I am stating it this way round deliberately: "the dashboard is fast" was true but not *useful*,
because it averaged a flat query together with a growing one. The flat part will stay flat and
the growing part has an obvious index when it matters. Knowing **which** of the two is which is
the part worth having.

---

## 6. What would you have asked us, if you could have?

In priority order. The first three change the architecture; the rest change output.

**1. Does the endpoint distinguish "key never seen" from "key seen, still processing"?**

This is the top question by a distance. **Without that distinction, failure mode B is
unrecoverable by any client**, mine included. My reconciliation depends on being able to ask
"have you seen this key?" and get an answer that is either *yes, here is the receipt* or
*no, never*. If the endpoint can only say "no current result", then "we never got it" and "we
have it and are working on it" are the same response, and there is no safe move: resubmitting
risks a duplicate, and not resubmitting risks a missed filing. Everything in Part 3 rests on
this being answerable.

**2. Is the 20-calls/60-seconds budget per TCC, per firm, or per source IP?**

It determines whether horizontal scaling helps *at all*. Per firm, more workers help. Per TCC or
per IP, adding machines does nothing and the only lever is the protocol. I assumed per firm,
which is the assumption most favourable to the design — so it is the one most worth checking.

**3. Can a single submit call carry filings for more than one client?**

From question 5: this single constraint is what takes the system down, and relaxing it is a 10×
improvement with no code change. If the restriction is a real IRS constraint I would design
around it differently — probably by making batch fairness explicit and by negotiating a higher
rate limit instead.

**4. Is vendor identity scoped per client or per firm?**

I assumed **per client**, because the 1099-NEC is issued *by* the client — aggregating one TIN
across two clients would produce one form for money two different payers paid. But if a firm
wants "this contractor was paid $400 by client A and $300 by client B" surfaced as a single
review item, that is a different aggregation and a different UI.

**5. Does a reversal recorded in January 2026 reduce the 2025 total?**

I assumed **no** — payment date governs, and the reversal is excluded as out-of-year but still
shown in the explanation. The alternative (reversals reverse into the year of the original) is
defensible and changes Box 1 for real vendors. This is a policy the firm should own, not one I
should pick.

**6. When a revised export drops a vendor below $600 *after* the form was accepted — correction,
void, or nothing?**

All three are defensible. A Type 1 correction at zero is the most conservative; doing nothing is
what many firms do in practice; a void has different penalty implications. This is exactly the
kind of question where guessing produces a system that is confidently wrong.

**7. Hold all of a client's filings until the client is clean, or transmit what is clean and
chase the exceptions?**

I chose **transmit as they become clean**, which makes "partially filed" a normal state rather
than an anomaly — and that decision propagates all the way into how the morning-after page reads.
The opposite choice (hold until clean) makes the page simpler and the filing deadline riskier. It
is a workflow preference, not a technical one, and I would rather have been told.

**8. What does the firm want to happen at 23:00 on 2 February with 400 blocked filings?**

Transmit them with a missing TIN and accept the known rejection, so that *something* is on
record? Or hold them and file late? My system holds, because a knowingly-wrong filing is a
decision no software should make on a firm's behalf. But it is a decision someone should make
deliberately before the night it matters, and the system should have a switch for it.
