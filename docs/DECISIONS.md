# Decision Log

Decisions where a reasonable engineer would have chosen differently, and why this one was chosen. Each entry names the file it lives in, so the code and the reasoning stay connected.

Format: **What was decided** · *Alternative rejected* · **Why**.

---

## D1 — The database is the enforcement point, not the application layer

**Files:** everywhere, but especially `db/setup.sql`, `V1__schema_core.sql`

Anywhere a design could be "a convention plus code review," prefer a constraint, a policy, or a grant.

*Alternative:* enforce tenancy, idempotency, and append-only-ness in Java service classes.

**Why:** the brief's exact words are *"firm isolation is structural, not conventional... so that a forgotten `where` clause fails safe."* A Java-layer guard fails **open** — one `createNativeQuery`, one JDBC template, one Flyway data fix, one psql session bypasses it. A database policy fails **closed** for every client of the database, including ones that don't exist yet. This principle drives D2, D5, D9, and D11.

---

## D2 — PostgreSQL Row-Level Security for firm isolation

**Files:** `db/setup.sql`, `V1__schema_core.sql`, `V7__rls_policies.sql`

| Approach | Forgotten `WHERE` | Migration cost | Verdict |
|---|---|---|---|
| Repository-layer guard (Hibernate `@Filter`) | **fails open** | none | rejected — exactly the "convention" the brief rules out |
| Discriminator + code review | **fails open** | none | rejected |
| **RLS** | **fails closed** | one policy per table | **chosen** |
| Schema-per-firm | fails closed | ×N schemas per migration | rejected — and `search_path` under a pool has the *identical* pooling hazard as `SET LOCAL`, so it buys nothing on risk while costing far more operationally |
| Database-per-firm | strongest | ×N databases, pools, connection budgets | right for 10 whale tenants, wrong for 500 small ones |

**Costs accepted, and stated rather than glossed over:** a predicate on every scan (nil in practice — it's `firm_id = <stable expr>` on the leading index column); generic plan caching can pool statistics across firms of very different sizes; `TRUNCATE` bypasses RLS entirely (handled by revoking it); FK violation errors can confirm existence of another firm's row (reduced to "does firm X have client N", which is already inferable).

---

## D3 — Three roles, and the runtime role owns nothing

**File:** `db/setup.sql`

`postgres` (bootstrap only) · `readiness_owner` (Flyway) · `readiness_app` (app, CLI, **and tests**).

*Alternative:* run everything as one role, as most projects do.

**Why:** this is the single most common way an RLS design is quietly decoration. Table **owners are exempt from their own policies** unless the table is `FORCE ROW LEVEL SECURITY`, and superusers bypass RLS unconditionally. So we do both: a non-owner runtime role *and* `FORCE`, so that neither mistake alone leaks data.

The corollary that matters most: **the test suite connects as `readiness_app` too.** A test suite that connects as a superuser proves nothing, because RLS is bypassed for it — it would pass identically against a completely unprotected database.

---

## D4 — `TRUNCATE` is explicitly revoked from the app role

**File:** `db/setup.sql`

**Why:** `TRUNCATE` is **not filtered by row-level security**. A role holding it on `ledger_line` can destroy both firms' data in one statement regardless of every policy on the table. Easy to miss, because `GRANT ALL` feels like the obvious default.

---

## D5 — An event trigger auto-enables RLS on new tables

**File:** `db/setup.sql` → `app.auto_enable_rls()` / `app_force_rls`

*Alternative:* remember to write `ENABLE ROW LEVEL SECURITY` in every migration.

**Why:** "the developer forgot to protect the new table" becomes an impossibility rather than a code-review item — consistent with D1. Honest caveats: creating an event trigger requires superuser (hence a one-time bootstrap step, not part of the app's runtime privileges), and implicit DDL can surprise a future maintainer, so it is documented at the top of the file. The meta-test in `FirmIsolationIT` is kept regardless, because it is what proves the trigger actually worked.

---

## D6 — `app.current_firm_id()` is `STABLE` and raises instead of returning NULL

**File:** `V1__schema_core.sql`

*Alternative A:* `VOLATILE`. **Why not:** a stable function is evaluated once per statement, so `firm_id = app.current_firm_id()` works as an index scan key. Volatile forces per-row evaluation and silently defeats every index built on `firm_id`.

*Alternative B:* return NULL when unset (`current_setting(..., true)`). **Why not:** that fails *silently* closed — queries return zero rows, which is safe but produces a maddening failure mode ("the dashboard is empty and nothing is in the log"). Raising `28000` fails *loudly* closed: identical safety, far better diagnosability, and something crisp for tests to assert.

---

## D7 — Composite foreign keys, not just a denormalized `firm_id`

**File:** `V1__schema_core.sql` (`client_firm_id_uk` as the FK target)

Every child table carries `firm_id` **and** links to its parent via `(firm_id, parent_id) → parent(firm_id, id)`.

*Alternative:* denormalize `firm_id` purely for query performance and rely on application code to set it consistently.

**Why:** RLS answers *"can you see it"*; the composite FK answers *"could it ever have been wrong."* Without it, a bad write could set `firm_id = 1` on a row pointing at firm 2's client, and the RLS predicate — which trusts that column — would happily serve it to firm 1. The composite FK makes that row **physically impossible to insert**. This is the difference between denormalizing for performance and denormalizing as an invariant.

---

## D8 — Every unique constraint is prefixed with `firm_id`

**File:** `V1__schema_core.sql` (`client_ref_uk`, `app_user_name_uk`)

**Why:** a *global* unique index leaks cross-firm existence through constraint-violation errors, and **RLS does not filter error messages**. Inserting a duplicate would tell firm 1 that firm 2 already holds that value. Prefixing with `firm_id` removes the channel entirely.

---

## D9 — Firm context via `set_config(..., is_local => true)`, never session-level `SET`

**File:** `config/FirmTransactionManager.java`, configured by `application.yml`

*Alternative:* `SET app.current_firm_id = ...` at the session level, or a Hikari `connectionInitSql`.

**Why:** a pooled connection is a long-lived server session, and a session-level `SET` **outlives the request that issued it**. If request A sets firm 1 and request B for firm 2 receives that connection but the plumbing fails to overwrite it, B reads A's data — a leak *caused by* the isolation mechanism. Transaction-local settings are unwound by Postgres at COMMIT or ROLLBACK, so there is no cleanup code to forget, because there is no cleanup code.

Three further properties earned this choice:
1. Outside a transaction, `set_config(..., true)` does nothing → an autocommit query has no firm context and hits 28000. **Non-transactional access fails closed and loudly.**
2. `set_config` accepts a **bind parameter**. `SET LOCAL app.current_firm_id = …` is a utility statement that *cannot* be parameterized, forcing string concatenation — i.e. reintroducing SQL injection inside the security control itself.
3. It stays correct behind PgBouncer in transaction-pooling mode, where session-level `SET` would be catastrophically wrong.

Deliberately **no** `DISCARD ALL` and no reset SQL: adding them would signal the primary mechanism isn't trusted. A test pins the pool to size 1 and asserts the setting is NULL on re-checkout instead.

---

## D10 — `FirmContext` is a plain `ThreadLocal`, not `InheritableThreadLocal`

**File:** `security/FirmContext.java`

*Alternative:* make it inheritable so async work "just works."

**Why:** inheritance is actively dangerous here. A child thread would silently inherit whichever firm was current when the pool thread was **created**, not the firm it is actually working for — a leak that looks like correct code. Losing the context fails loudly (28000); inheriting the *wrong* context fails silently. We take the loud failure every time. Anything needing context on another thread is handed it explicitly.

---

## D11 — JDBC, not JPA

**File:** `pom.xml`

*Alternative:* Spring Data JPA, the default choice for a Spring Boot project.

**Why:** the two performance requirements are met by a `COPY` stream and a set-based SQL aggregation, and the correctness requirements are met by exact transaction boundaries. An ORM would hide the precise statements this project is judged on, and its first-level cache and flush semantics would obscure exactly the commit boundaries that Part 3 is *about*. A related consequence appears later: TIN decryption is an explicit service call at two sites rather than a JPA `AttributeConverter`, so "where does plaintext exist" stays a grep-able question.

---

## D12 — The test profile scales configuration instead of mocking clocks

**File:** `application-test.yml`

*Alternative:* mock `Clock`, mock the rate limiter, use a fake scheduler.

**Why:** the brief says failure rates, latency, and delays must be configurable "including zero, for tests" — so configurability is treated as the organizing principle rather than a checkbox. Setting `rate.window: 600ms` and `ack-delay: 0ms` means the **real** sliding-window limiter, the **real** backoff policy, and the **real** reconciliation code run in every test, just 100× faster. A mocked limiter would test the mock.

---

## D13 — Postgres-backed job queue, not Redis/Celery/Quartz

**File:** (Phase 5) `transmission/`, `V4__schema_transmission.sql`

*Alternative:* Redis-backed queue, or a dedicated scheduler.

**Why:** it lets filing state, job state, and rate-budget consumption **commit in one transaction**. That single property is the spine of the whole zero-duplicate design: there is no window in which a token is spent but the state change is lost, or a job exists for a row that doesn't. A Redis queue re-introduces exactly the two-phase problem the design exists to eliminate, and adds infrastructure the brief doesn't need.

---

## D14a — `RlsGuard` is production code, not a test

**File:** `security/RlsGuard.java`

*Alternative:* assert the RLS invariants only in the integration test suite.

**Why:** the test suite proves the policies *work*; it cannot prove they are still switched on in whatever environment the app actually booted into. Every way of breaking RLS is silent — connect as superuser, connect as owner without `FORCE`, grant `BYPASSRLS` for a debugging session and forget to revoke it, add a table and forget the policy. In all four cases the application keeps working perfectly and simply stops isolating firms. Nothing throws, nothing logs.

Asserting on every boot costs one round trip and converts that entire class of silent failure into a process that will not start. It also protects the reviewer's machine after a hand-run migration or a psql session.

---

## D15 — Money is `long` cents, enforced by a schema test

**File:** `support/Money.java`

*Alternative:* `BigDecimal` / `NUMERIC(14,2)` throughout.

**Why:** the threshold rule is *"$600.00 or more, inclusive."* As integer cents that is `reportableCents >= 60_000` — an exact integer comparison with no epsilon to pick and no rounding mode to argue about. Under `double`, the entirely ordinary payment sequence `199.99 + 200.01 + 200.00` sums to `600.0000000000001`; a naive `>= 600.0` would pass **for the wrong reason** while a `<= 600` check elsewhere in the same system would fail. Summation over ~1M rows is also exact and allocation-free as `sum(bigint)`.

`toCents` uses `longValueExact()`, which **throws** on sub-cent input rather than truncating — the importer turns that into a `SUB_CENT_AMOUNT` rejection, because silently discarding a fraction of a cent makes the books disagree with the source by an amount nobody can later account for.

The invariant is enforced rather than documented: a test scans `information_schema.columns` for float types and fails the build if one appears.

---

## D16 — `Tin` is a class, not a record

**File:** `security/Tin.java`

*Alternative:* `record Tin(String digits, Kind kind)`, which is the idiomatic Java 21 choice.

**Why:** records generate a `toString()` that prints every component. For this type, that generated method is a data breach with an auto-generated implementation. `toString()` is the *actual* leak path in practice — string concatenation, `log.info("{}", vendor)`, Jackson's default serializer, Hibernate parameter tracing, and `new IllegalArgumentException("bad TIN: " + value)` all route through it. Overriding it to emit `***-**-6789` makes the **safe rendering the default rendering**, which removes the whole class of mistake instead of relying on every future call site to remember.

---

## D17 — A missing TIN is data, not a malformed row

**File:** `security/Tin.java` → `parse`

*Alternative:* reject rows with no TIN at import time.

**Why:** the brief is explicit that *"a missing TIN doesn't remove the filing obligation... never a reason to silently skip the vendor."* Rejecting at import would drop the vendor from the book entirely, which is exactly the failure being guarded against. So the split is:

- **Rejection** = structural, the row cannot be represented (unparseable date, ragged row, sub-cent amount).
- **Exception** = semantic, the row imports fine but a human must act (missing TIN, malformed TIN, unknown payment method).

*"Missing TIN is not a malformed row"* is the confusion the brief is testing for, and the split makes the right answer structural rather than a code-review note.

---

## D18 — Blind index for TIN equality; the ledger stores no plaintext TIN

**File:** `security/TinCryptoService.java`

*Alternative A:* store the TIN in plaintext on `ledger_line` and group on it directly. Simplest, fastest, and what most implementations do.
*Alternative B:* deterministic encryption, so the ciphertext itself is groupable.

**Why not A:** Part 2 requires grouping ~1M ledger rows by TIN. Putting the most sensitive value in the system into the million-row table — and therefore into every backup, replica, and `pg_dump` of it — is a far bigger exposure than any cryptography applied to it can offset. The ledger stores `vendor_id` and a blind index; plaintext lives only on `vendor` (tens of thousands of rows), encrypted.

**Why not B:** both deterministic encryption and a keyed HMAC leak equality — that is the unavoidable price of `GROUP BY`. The HMAC wins on three counts: it uses a **separate key**, so compromising the grouping key decrypts nothing; it is one-way, so the column is not a decryption oracle; and it is a fixed 32 bytes that indexes like any other `bytea`.

**The limitation, stated rather than hidden:** the TIN space is 10⁹, so a leaked blind-index key makes the whole column brute-forceable in seconds. Truncating the index to force collisions would blunt that, but it would merge genuinely distinct vendors and break determination's correctness — not a trade worth making. This goes in the write-up as a known limitation, which is a better answer than presenting the blind index as strong.

---

## D19 — Randomized encryption + AAD binding, not deterministic

**File:** `security/TinCryptoService.java` → `encrypt` / `aad`

Fresh nonce per write, and GCM additional authenticated data binding the ciphertext to `firm_id ‖ client_id ‖ blind_index`.

**Why:** randomized encryption means the ciphertext column leaks no equality at all — equality lives *only* in the blind index, deliberately and in one place. The AAD binding means a ciphertext copied from one vendor row to another **fails to decrypt** rather than silently attributing one contractor's TIN to a different contractor. Cheap integrity for a value with legal consequences.

There is a subtle consequence that matters in Part 3 and is easy to get wrong: because encryption is randomized, the filing `content_hash` must be computed over the **plaintext** TIN, never the ciphertext. Hashing ciphertext would change the hash on every re-encryption or key rotation, silently changing every idempotency key and causing already-live filings to be resubmitted under new keys.

---

## D20 — Grants and RLS live in a *repeatable* migration

**File:** `db/migration/R__grants_and_rls.sql`

*Alternative:* a versioned migration (`V8__grants.sql`) after the last schema migration.

**Why:** grants and policies must apply to *every* table, so they have to run after the final `CREATE TABLE`. A versioned file pinned to a number would need renumbering every time a schema migration is added, and — worse — would silently miss any table created by a later migration. Flyway runs repeatable migrations last, after all versioned ones, on every migrate. That is exactly the required semantics, and it turns the ordering problem from something to remember into something structurally impossible.

Everything in the file is idempotent, so re-running against an unchanged schema is a no-op.

---

## D21 — Three independent mechanisms enforce RLS, not one

**Files:** `db/setup.sql` (event trigger) · `R__grants_and_rls.sql` (sweep) · `security/RlsGuard.java` (boot assertion)

*Alternative:* pick whichever one is most elegant and rely on it.

**Why:** every way of breaking RLS is silent, and the three mechanisms fail in different ways. The event trigger protects at `CREATE TABLE` time but can be dropped, and requires superuser to install. The repeatable sweep catches anything the trigger missed but only runs at migrate time. `RlsGuard` catches everything at boot but cannot fix it. Together they cover creation, migration, and startup. Any one alone leaves a window.

---

## D22 — `REFERENCES` and `TRIGGER` are withheld alongside `TRUNCATE`

**File:** `R__grants_and_rls.sql`

**Why:** `TRUNCATE` is the well-known one (not filtered by RLS). The other two are subtler. `REFERENCES` would let the app role create foreign keys, and **FK constraint checks run as the table owner and are not subject to RLS** — so a crafted constraint becomes an existence-disclosure channel across firms. `TRIGGER` would let the app role attach code that executes with owner privileges. `GRANT ALL` hands out all three by reflex.

---

## D23 — Hand-rolled SplitMix64 + xoshiro256\*\*, not `java.util.Random`

**Files:** `seed/SplitMix64.java`, `seed/Xoshiro256StarStar.java`

*Alternative:* `java.util.Random`, `SplittableRandom`, or `RandomGenerator` from the JDK.

**Why:** the generator's contract is that `--seed=42` produces a **byte-identical** corpus on any machine, any JDK, forever. That cannot rest on a JDK class whose algorithm is an implementation detail — `SplittableRandom`'s split behaviour and `Random`'s stream methods are not specified as stable across releases, and a future JDK could legitimately change them. Golden-file tests would then break for reasons unrelated to this project. xoshiro256\*\* is fixed by its specification, ~40 lines, passes BigCrush, and is faster than either JDK option.

Two sub-decisions inside it, both about not being subtly wrong at scale:
- **`nextInt` uses Lemire multiply-shift with rejection, not `% bound`.** Modulo bias is invisible in most uses, but across a million rows it systematically skews vendor counts and payment amounts — a corpus that is wrong in a way that is hard to notice and harder to explain.
- **`nextGaussian` deliberately does not cache the second Box–Muller variate.** Caching makes a draw depend on whether an earlier call was odd or even, which silently couples unrelated call sites: add one draw upstream and every later value shifts.

---

## D24 — Random streams are keyed by hierarchical string path

**File:** `seed/RandomStreams.java`

*Alternative:* one sequential generator threaded through the whole generation run.

**Why:** this is the most important decision in the seed generator, above the choice of PRNG. With one shared stream, **adding a single new random draw anywhere** — a memo generator, an extra address field — shifts every subsequent value and reshuffles the entire corpus. Every golden test breaks, every planted fixture moves, and the diff is unreviewable. Path-keyed streams mean a new draw creates a *new path* and touches nothing that already exists.

It also buys two things that would otherwise need real work: parallel generation that remains byte-identical (each client owns its stream, buffers concatenate in client order), and independently reproducible slices (client 0142 can be regenerated without the other 499, which makes debugging one planted case tractable).

---

## D25 — TIN log masking covers stack traces, and is labelled a backstop

**Files:** `security/TinMaskingConverter.java`, `TinMaskingThrowableConverter.java`

**Why cover throwables:** `new IllegalArgumentException("bad TIN: " + value)` is a natural thing to write, JDBC drivers echo parameters, and CSV parsers quote the offending line. All of that surfaces through the throwable, not the message. Masking only the message would leave the larger hole open.

**Why label it a backstop:** presenting a regex scrubber as the primary control invites people to stop being careful upstream. The real controls are `Tin.toString()` rendering masked by default, the ledger storing no plaintext TIN at all, and `log_parameter_max_length = 0`. The accepted imprecision is stated rather than hidden — false positives (a masked invoice number costs someone 30 seconds; a leaked SSN is a disclosure incident) and false negatives (a TIN split across two arguments), the latter being exactly why it cannot be primary.

---

## D26 — An unknown payment method counts toward the threshold

**File:** `ledger/PaymentMethod.java`

*Alternative:* treat unrecognised methods as card (safer-sounding: fewer forms), or reject the row.

**Why:** the two error directions are not symmetric. Guessing "card" would **suppress** a filing that may well be required — a missed 1099-NEC, which is a penalty notice nobody discovers until the IRS sends one. Guessing "not card" at worst produces a form that turns out not to have been needed, which is visible and correctable. Both are wrong; only one is silent. `UNKNOWN` therefore counts toward the threshold *and* raises an exception, so a human resolves it either way.

A related judgement inside the same file: **bare `"zelle"` maps to `UNKNOWN`, not TPSO.** Consumer Zelle transfers are not 1099-K reportable because the network doesn't settle funds the way a TPSO does, but `"zelle business"` is different. Rather than guess on a genuinely ambiguous string, it goes to a human. Documented as an assumption.

---

## D27 — `entry_type` is a column, not inferred from the sign of the amount

**File:** `ledger/EntryType.java`

*Alternative:* treat any negative amount as a reversal. Determination sums signed amounts anyway, so arithmetically this works.

**Why:** two reasons, and the second is the stronger one.

1. A negative amount is genuinely ambiguous — reversal, vendor refund, voided cheque, or a bookkeeper's sign error. Those are different facts and the human resolving an exception needs to know which.
2. **Explainability.** The brief requires showing *"which payments counted, which didn't and why."* A row that explains itself as *"reversal of txn QB-88213"* is useful; one that says *"negative"* is not.

`VOID` is excluded outright rather than netted, because it never moved money — counting it and then subtracting it would inflate both the gross and the reversal figures shown in the explanation, making the arithmetic on screen look wrong even though the total is right.

---

## D28 — A blank expense category is presumed `SERVICES`

**File:** `ledger/ExpenseClass.java`

*Alternative:* presume not-services, and only count explicitly categorised service payments.

**Why:** hand-maintained spreadsheets frequently have no category column at all. Presuming "not services" would silently suppress filings for **every client whose bookkeeper never categorised anything** — which is exactly the population most likely to need the firm to get this right. Erring toward services produces at worst an extra form a human can question; erring the other way produces a missing form nobody notices until a penalty notice arrives.

This materially changes which vendors get filed for, so it is called out in the README's decisions log rather than buried — a reviewer should see it was a choice, not an oversight.

---

## D29 — No fuzzy vendor-name matching, ever

**File:** `ledger/VendorNameNormalizer.java`

*Alternative:* trigram similarity or Levenshtein distance to auto-merge near-identical vendor names.

**Why:** the error directions are wildly asymmetric.

- A **false merge** reports one contractor's income under another contractor's identity: a wrong tax form, a penalty, and a disclosure of one party's payment history to another. It is also nearly invisible — the totals look entirely plausible.
- A **false split** produces two vendors where there should be one: visible, correctable, and it surfaces as an exception.

So the rule is *only merge on evidence strong enough to bet a tax filing on*. A shared TIN is that strong. A similar name is not. Trigram similarity **is** computed — but only to render a *suggested* merge on the exceptions page, never applied automatically, with a `vendor_alias` table recording the human's decision so the resolver honours it deterministically thereafter.

Two details in the same file that would be bugs if missed: `toUpperCase(Locale.ROOT)` rather than the default locale (in a Turkish locale `"i"` upper-cases to a dotted capital I, which would make vendor identity differ by machine), and NFKD decomposition so `"Núñez"` and `"Nunez"` resolve together — with accented names deliberately present in the corpus, because otherwise that code path would look tested and be untested.

---

## D30 — `PaymentRow` holds CSV strings, not typed fields

**File:** `seed/PaymentRow.java`

*Alternative:* a typed record with `LocalDate`, `long`, and enums, rendered to CSV at write time.

**Why:** the generator must emit rows that are *deliberately invalid* — an unparseable date, `"N/A"` in the amount column, `"ABC-DEFGH"` as a TIN. None of those can be represented by `LocalDate` or `long`, so a typed model forces a parallel "broken row" type and two rendering paths, one of which inevitably drifts from the other.

Holding CSV text throughout means defect injection is just "replace one field with this string", the writer has exactly one code path, and **what the test asserts about is literally what the importer will read**. `PaymentRow.of(...)` keeps ordinary generation type-safe right up to the boundary.

---

## D31 — Rejected vs. exception is an enum, not a convention

**File:** `seed/DefectClass.java`

Every planted defect carries its expected `Outcome`: `REJECTED` (structural — no date to store, no amount to sum), `IMPORTED_WITH_EXCEPTION` (semantic — storable, needs a human), or `WARNED` (duplicate key, collapsed during staging dedupe).

**Why:** *"a missing TIN is not a malformed row"* is precisely the confusion the brief is testing for. Making the distinction a type rather than a code-review note means the generator, the importer, and the tests all reference the same source of truth — and the rejection **report** is asserted, not merely the fact that the import survived.

`DUPLICATE_KEY_IN_FILE` is planted specifically because QuickBooks exports genuinely double-emit rows, and `ON CONFLICT DO UPDATE` throws *"command cannot affect row a second time"* when two source rows share a conflict key. It proves the importer survives a real export quirk that a naive upsert does not.

---

## D32 — Fixture expectations are written by hand, not derived from the generated rows

**File:** `seed/FixturePlanter.java`

Each case states its answer explicitly (`reportableCents = 25_000`) rather than summing the rows it just emitted.

*Alternative:* compute the expectation from the generated data — less duplication, no risk of the two drifting apart.

**Why:** deriving it would let the fixture and the system under test **share a misconception**. If both summed gross instead of net, both would agree, the test would pass, and the system would file a form for $800 that was never owed. Writing the number by hand from the brief's own wording makes the fixture an *independent* statement of what is correct — computed by different code, from a different direction. That is the entire value of it.

The same logic drives recording the full decomposition (`gross`, `cardExcluded`, `reversal`, `reportable`) rather than just the final figure: an implementation can reach the right total by the wrong route — netting a card refund against non-card payments happens to balance in some cases and is badly wrong in others. Asserting the parts pins the arithmetic, not just its result.

---

## D33 — Case 3 ships with a paired negative fixture

**File:** `seed/FixturePlanter.java` → `EXACTLY_SIX_HUNDRED` + `JUST_UNDER_THRESHOLD`

The brief asks for a vendor totalling exactly $600.00. We also plant one at $599.99.

**Why:** either assertion alone is nearly worthless. A system that files for **everything** passes the $600.00 test. A system that files for **nothing** passes the $599.99 test. Only the pair pins the boundary, and only the pair catches `>` where `>=` was needed.

The components are also deliberately non-round — `199.99 + 200.01 + 200.00`. Under `double` those sum to `600.0000000000001`, so a floating-point implementation would pass a `>= 600.0` check *for the wrong reason* while failing a `<= 600` check elsewhere in the same system. In integer cents the sum is exactly `60000`.

---

## D34 — Three unbriefed cases planted, because that is where identity actually breaks

**File:** `seed/FixturePlanter.java`

Beyond the brief's six: **4b** (TIN backfill promotion), **4c** (one name, two TINs), **5b** (card mix *above* threshold).

**Why each:**

- **4b** is the single most likely correctness bug in Part 2. Four blank-TIN rows ($520) plus one TIN-bearing row ($180) under the same name: the naive reading of *"vendors are identified by TIN"* splits them and files **nothing** — a missed $700 obligation that no test derived from the brief's six cases would catch.
- **4c** is why promotion must be *asymmetric*. One TIN under many names merges (a TIN is strong); one name under many TINs must not (two businesses called "Smith Consulting" is ordinary, not an edge case). Expected vendor count is **3** — the two TIN vendors plus the unresolved name unit — so the fixture asserts a *non*-merge.
- **5b** pins the reported amount, which the brief never states. A form *is* required and Box 1 is **$650, not $2,400**: the card portion is excluded from the reported amount as well as the threshold, because the processor already reports it on 1099-K and double-reporting triggers an under-reporting notice against the contractor.

---

## D35 — Planted fixtures are invisible in the CSV

**Files:** `seed/FixtureCase.java`, `seed/FixtureManifest.java`

No flag column, no reserved client, no distinctive ordering. Locations live out-of-band in `fixtures.json`.

**Why:** if the corpus flagged its own fixtures, the system would only be proven to handle rows it had been told to look at. Planted cases therefore look exactly like ordinary data, scattered through ordinary clients.

Two supporting choices: each case is planted **25× across both firms** (N=1 proves a rule fires; N=25 across two firms proves it fires *consistently* and does not leak across the tenancy boundary), and fixture TINs all begin `99` so a human debugging in psql can find them with `WHERE tin_raw LIKE '99-%'` rather than cross-referencing a file.

---

## D36 — Database-dependent beans gate on a property, never `@ConditionalOnBean`

**Files:** `config/DataSourceConfig.java`, `security/RlsGuard.java`, `Application.java`

*Alternative (and the first thing I wrote):* `@ConditionalOnBean(DataSource.class)` / `@ConditionalOnBean(JdbcTemplate.class)`.

**Why it was wrong:** bean conditions on a user `@Component` or `@Configuration` are evaluated during **component scanning**, which runs *before* auto-configuration has contributed the `DataSource` or `JdbcTemplate`. The condition therefore evaluates false even when the bean will exist moments later.

This was caught by actually running it — `RlsGuard` silently never executed. The failure mode is exactly the one the guard exists to prevent: **a security check that is quietly disabled is worse than no check, because it manufactures false confidence.** The identical flaw applied to `transactionManager`, where a false condition would have let Spring's default `JdbcTransactionManager` take over and leave every transaction without firm context, with the application starting perfectly and reporting nothing.

`@ConditionalOnProperty` is evaluated from the Environment and does not depend on bean ordering. `Application` sets `readiness.database.enabled=false` for the one command (`seed`) that genuinely needs no database.

---

## D37 — `baseline-on-migrate` is deliberate, not a workaround

**Files:** `application.yml`, `db/setup.sql`

`db/setup.sql` pre-seeds schema `app` with `auto_enable_rls()` and its event trigger, so Flyway finds a non-empty schema on first run and refuses to proceed without a baseline.

*Alternative:* move the function to `public` so `app` starts empty.

**Why not:** the event trigger requires **superuser** to create, which is precisely why it cannot live in a migration (migrations run as `readiness_owner`, deliberately not a superuser — see D3). Putting it in `public` to satisfy Flyway would scatter the schema for a tooling reason rather than a design one. Baselining at version 0 keeps every `V1+` migration running normally and documents the real situation: the schema has a superuser-installed prologue.

---

## D38 — Staging tables live in their own schema, not `app`

**Files:** `db/setup.sql`, `V2__schema_ledger.sql`, `ImportPipeline`

*Alternative:* grant `CREATE` on schema `app` to the runtime role so it can make per-run staging tables there.

**Why:** the runtime role must be able to create staging tables, but granting `CREATE` on the *tenant* schema would let the application add arbitrary objects alongside the real data — a privilege surface with no upside. A separate `stg` schema gives it `CREATE` exactly where it needs it and nowhere else. Staging holds no tenant rows: `firm_id` is stamped by the `INSERT … SELECT` that moves rows into `app.ledger_line`, where the `WITH CHECK` policy validates it.

---

## D39 — `app.firm` relaxes SELECT, and only SELECT

**File:** `V1__schema_core.sql`

Every other table uses `firm_id = app.current_firm_id()`. `app.firm` permits `SELECT` of all rows to the app role, and permits writes to **no one** except the migration/owner role.

*Alternative (what I wrote first):* `using (id = app.current_firm_id())`.

**Why it doesn't work:** it creates a bootstrap paradox. Resolving a firm slug to an id requires reading `app.firm`, which requires already knowing the firm id. Nothing could provision a firm or look one up, and every entry point would need an out-of-band way to learn its own identity.

**Why relaxing is defensible:** `app.firm` holds no tenant data — a slug, a display name, and an IRS transmitter control code. Knowing that a firm called "Harborline CPA" exists discloses nothing about its clients, vendors, payments, or filings, all of which stay strictly isolated. **What is protected is the data; what is readable is the directory.** Writes stay closed because provisioning a tenant is an operational act, not something the running application should be able to do.

---

## D40 — RLS policies apply to every role, including the owner

**Files:** `db/setup.sql`, `R__grants_and_rls.sql`, `V3__seed_reference.sql`

The auto-created `firm_isolation` policy has no `TO` clause, so it binds every role rather than just `readiness_app`.

*Alternative:* scope policies to the app role and give the owner an exemption so migrations can seed data freely.

**Why:** an owner exemption is a privileged path that can write a row into the wrong firm, and privileged paths are exactly what "structural, not conventional" is meant to eliminate. With no exemption there is no such path at all.

The cost is visible and, I think, a feature: a migration that seeds firm-scoped data must establish firm context exactly as the application does — see the per-firm loop in `V3__seed_reference.sql`. That isn't a workaround; it's the policy working, demonstrated in the migration itself.

---

## D41 — Two speculative indexes removed after measuring

**File:** `V2__schema_ledger.sql`

`ledger_line` originally had six indexes; it now has three. Removed: `(firm_id, client_id, tin_bidx, vendor_name_norm)` and `(firm_id, vendor_id)`. The separate `unique (firm_id, id)` was folded into a composite primary key.

**Why:** both removed indexes looked useful and were not. Determination reads an entire client-year through `ledger_determination_ix` and aggregates in memory, so an identity index is never probed — only maintained. The per-vendor explainability query reaches one client's few thousand rows through the same index and filters in memory, for one human at a time.

Each index costs one write per row. On a 500k-row load, two speculative indexes were consuming roughly a third of the merge budget to serve queries that never used them. **This was measured, not assumed** — the import went from 152 s to 136 s on the index change alone.

---

## D42 — `work_mem = 512MB` for the merge, to keep the trigger's transition table in memory

**File:** `ImportPipeline.mergeLedger`

**Why:** this was the single largest performance fix, and it was found by measurement rather than intuition. Disabling the dirty-marking trigger cut the merge from 90 s to 68 s, so the trigger cost ~22 s. But the `DISTINCT` over the rows is cheap — the real cost was that a statement-level trigger with a transition table makes Postgres materialise every affected row into a tuplestore, roughly 100 MB for a 500k-row merge. At `work_mem = 128MB` that was spilling to disk.

The alternative was to drop the trigger for INSERTs and compute dirty marks in the importer, which would have been faster still — and would have thrown away the "cannot be forgotten by any writer" guarantee that justified the trigger in the first place (D-dirty-marking). Raising a memory limit preserved the property and removed the cost. Total import: 152 s → 59 s.

`synchronous_commit = off` is set alongside it, justified by the same idempotency that gives the pipeline its restartability: a commit lost to an OS crash costs a re-run of a command that was already safe to re-run.

---

## D43 — The seed generator renders each file in its own dialect

**Files:** `SourceDialect.outputHeaders`, `PaymentRow.renderedFor`, `SeedGenerator.renderFor`

**Why:** originally every export file was canonical CSV — ISO dates, plain decimals, canonical headers — distinguished only by filename and a `sourceSystem` label. The importer then parsed the QuickBooks-labelled file with QuickBooks date rules and rejected **223,783 of 499,875 rows**.

That bug was worth having, because it exposed something worse than itself: the three dialects existed but were never exercised. Every file already matched the canonical parser, so the header-alias map, the date-format list, and the amount normalizer were dead code that happened to be tested against data they already fit.

Now QuickBooks emits `05/25/2025` and `"1,178.05"` under headers like `Num` / `Name` / `Tax Id`, Xero emits ISO dates under `Payment Id` / `Contact`, and the spreadsheet emits `14-May-25` and `$291.79` under `Company` / `Ein` / `How Paid`. Rejections fell to 2,762 (0.55%) — all of them the deliberately planted defects.

**The systemic-failure guard is what caught it.** Rather than importing 55% of a book and reporting success, the run aborted with `TOO_MANY_REJECTIONS` and named the number. That guard existed on principle; this is the first time it earned its place.

---

## D44 — The manifest declares which tax years it covers, not just which year it files

**Files:** `ExportManifest`, `ImportPipeline.tombstone`

Deletion is scoped to `(declared clients, source systems, **covered tax years**)`. `taxYear` is the *filing* year and is not the coverage scope.

**How this was found — and why it matters that it was found this way.** The revised-export fixture stated 16 tombstones; the importer produced 14. Because the expectation was **written by hand rather than derived from the implementation** (D32), the two could disagree — and the disagreement was the implementation's fault, not the fixture's.

The bug: a real accounts-payable export contains payments either side of the year boundary, so an export "for 2025" legitimately carries 2024 and 2026 rows. Scoping deletion to `tax_year = 2025` meant a row the bookkeeper deleted from an adjacent year was imported once and then **never removable** — lingering in the ledger forever, invisible to every subsequent import, and silently inflating that vendor's history.

Scoping to *all* years would have been worse: a later 2024-only export would wipe every 2025 row for those clients. Declaring exactly which years the file speaks for resolves both, and after the fix the fixture and the importer agree exactly: 35 inserted, 22 updated, 16 tombstoned, 497,075 unchanged.

Had the fixture computed its expectation from the generated rows, both sides would have shared the same blind spot and the test would have passed.

---

## D45 — `bit_xor` for the book checksum, with its weakness stated

**File:** `ingest/BookChecksum.java`

*Alternative:* `md5(string_agg(row_hash, '' ORDER BY id))`.

**Why:** the `ORDER BY` forces a sort of every live row — hundreds of megabytes of sorting purely to make the result order-independent. XOR is commutative and associative, so it needs no ordering at all: one unordered aggregate over a sequential scan, and it parallelises.

**The weakness, stated rather than glossed:** XOR is insensitive to a row appearing an even number of times, so the checksum alone cannot distinguish "row X present once" from "row X present twice plus row X missing". That is precisely why `verify-import` checks **seven** independent signals rather than trusting one — and why the strongest of them is not the checksum at all but **"the dirty set is empty"**, which proves not merely that nothing changed but that nothing was even *considered* changed.

---

## D46 — Firm context is established outside the transaction, never inside

**Files:** `BookChecksum`, `VerifyImportCommand`

`FirmContext.runAs(firmId, () -> service.transactionalMethod())`, not `@Transactional void m() { FirmContext.runAs(...) }`.

**Why:** `FirmTransactionManager` reads the context at transaction *start*. Setting it inside a `@Transactional` method is too late — the transaction has already begun and is rejected for having no firm.

I got this backwards first, and the failure was instructive: `FirmContext.runAs` around a bare `jdbc.update` sets the thread-local but starts no transaction, so `set_config` never runs and `app.current_firm_id()` raises `28000`. That is the design working exactly as intended (D9) — **non-transactional access fails closed and loudly** rather than quietly returning another firm's rows. The confusing error was the isolation guarantee doing its job on my own mistake.

---

## D47 — Enum-to-weight mapping is explicit, never ordinal

**File:** `seed/SeedConfig.PAYMENT_METHOD_ORDER`

The seed generator originally indexed `PaymentMethod.values()` with a cumulative-weight array whose comment described a *different* order than the enum's declaration.

**What that cost:** the 18% intended for credit card landed on wire. Card + TPSO came out at **8% instead of 25%**, so the corpus under-exercised the Form 1099-K exclusion — the single rule that distribution exists to stress — by a factor of three.

**What makes it worth recording:** *nothing failed.* The generator ran, the import ran, determination ran, and **all nine planted fixtures passed** — because fixtures pin specific vendors, not the background distribution. It surfaced only when the disposition histogram was compared against the intended mix: `EXCLUDED_CARD_TPSO` at 6.2% where ~19% was expected.

Ordinal-coupled arrays break silently when someone reorders an enum for readability. Naming the constants makes the pairing checkable by eye and impossible to shuffle by accident. After the fix, `EXCLUDED_CARD_TPSO` is 19.3% — exactly 25% × (1 − 0.18 non-services) × (1 − 0.058 out-of-year).

The general lesson, and the reason this sits in the decision log rather than a commit message: **fixtures verify the rules; only distribution checks verify the data the rules run on.** Both are needed.

---

## D48 — Determination is set-based SQL, with an independent Java oracle

**Files:** `determination/DeterminationEngine.java`, `determination/PaymentClassifier.java`

*Alternative:* a Java rules engine over hydrated rows, which is the more conventional and more readable choice.

**Why SQL:** it wins on both of the brief's criteria simultaneously. On speed, hydrating a million rows over JDBC is 30–60 s of wire time and GC *before any rule runs*, against a 60 s budget; the SQL pass is 15.5 s end to end. On explainability — the argument that actually settles it — the per-payment reason is a `CASE` expression **in the same projection** that computes the counted amount, so the explanation and the total cannot drift. They are the same row of the same query.

**Why the Java classifier exists anyway:** tests encode the same understanding the implementation does, so a misunderstanding produces a *passing* test. The strongest available correctness argument is two implementations, written at different levels of abstraction, that must agree on every input. `PaymentClassifier` takes primitives and returns an enum — it shares no code, no framework, and no persistence layer with the SQL, precisely so it cannot share a bug.

---

## D49 — Classification order is the rule precedence, and card must precede reversal

**Files:** `determination/Disposition.java`, `DeterminationEngine` classification CTE

The `CASE` arms are ordered: out-of-year → void → non-services → **card/TPSO** → zero → reversal → counted.

**Why the order is load-bearing:** a refund of a card payment carries `payment_method = credit_card` *itself*. Testing "is the amount negative?" before the card check would **count the refund while excluding the original payment**, dragging the vendor's reportable total below any amount they were actually paid.

That bug is invisible in aggregate — the total looks plausible, no exception fires, and the vendor simply receives no form. Classifying on the row's own payment method first makes it unrepresentable.

Two related orderings: out-of-year is tested **first** so those payments appear in the explanation as *excluded* rather than absent (a payment that silently vanishes is indistinguishable from one never imported); and `VOID` is excluded outright rather than netted, because it never moved money — netting it would inflate both the gross and the reversal figures on screen.

---

## D50 — Version the decision, materialise the evidence

**File:** `V4__schema_determination.sql`

`vendor_determination` is SCD-2 (`valid_from`/`valid_to`, only dirty units rewritten). `payment_determination` is overwritten in place.

**Why the asymmetry:** when a penalty notice arrives in June, the question is not "what do we believe now" but *"what did we believe when we filed, and under which rules"* — so the decision, the ruleset hash, and the normalizer version must be preserved. But 1M payment rows × N runs of history is unjustifiable storage for something always reconstructible: the immutable vendor-level snapshot plus the ledger rows re-derive any past per-payment verdict.

Because only dirty units are rewritten, storage grows with **change** rather than with `runs × vendors` — re-running determination over an unchanged book adds no rows at all.

---

## D51 — `unique (firm_id, filing_id, filing_generation)` with **no** `WHERE` clause

**File:** `V5__schema_transmission.sql` → `uq_one_submission_per_epoch`

*Alternative:* a partial unique index on a "currently live" flag, e.g. `where batch_state in ('DISPATCHED','SUBMITTED')`.

**Why not:** a partial index requires maintaining that flag correctly. That is code, and code is where bugs live — the very bugs this constraint exists to catch. A predicate-free constraint says something much stronger and needs nothing kept in sync: *a filing may be submitted at most once per attempt epoch, **ever***. Not "at most one live batch" — at most one, full stop.

To submit a filing again you must first bump its `generation`, and generation only bumps on **positive proof** the previous epoch is dead (a status call returning `Unknown`) or on human remediation. Every duplicate-producing bug I can construct has to violate this constraint to reach the wire, and it cannot: the insert fails and the planning transaction rolls back.

---

## D52 — Errors classified by epistemic class, not HTTP status

**File:** `spi/TransmissionExceptions.java`

`NotDispatched` (provably nothing sent) · `Indeterminate` (**the default**) · `RejectedRequest` · `RateLimited`.

**Why:** the only question that matters after a failed submission is *could the server have recorded anything?* An HTTP status is a poor proxy — a 500 might mean rejected at the front door or every filing written and the response lost, and under failure mode B an error is returned specifically *after* everything was recorded.

Putting the taxonomy in the type system means a future HTTP client that maps unrecognised exceptions to the default gets `Indeterminate` and is therefore **safe by accident**. That is the right direction for a default to fail in. `NotDispatched` is `sealed`, so no future subclass can quietly assert "nothing happened" without being listed.

---

## D53 — The stub records; the test judges

**File:** `V6__schema_irs_stub.sql` → `recorded_filing` has no uniqueness constraint

*Alternative:* have the stub reject duplicate `client_reference` values, which would "catch" duplicates immediately.

**Why not:** it would catch them *for the wrong reason*. A duplicate-producing bug would surface as a stub exception, and the test would pass having proved only that the stub enforces something. The assertion that actually matters —

```sql
select client_reference, count(*) from irs_stub.recorded_filing
 group by client_reference having count(*) > 1   -- must be empty
```

— is made against **the IRS's own books**, not against our system's beliefs. So the stub records faithfully, including duplicates, and the test does the judging.

The same reasoning puts the stub's state in **PostgreSQL rather than memory**: the required kill-and-resume test needs the "IRS" to outlive the killed worker. An in-memory stub forgets everything on SIGKILL, so every restart would look like a clean slate and mode B would be untestable — the test would pass while proving nothing.

---

## D54 — `StubStore` is a separate bean because `@Transactional` self-invocation is inert

**File:** `transmission/stub/StubStore.java`

I first wrote `recordSubmission` and `logCall` as `@Transactional` methods **on `StubIrsClient` itself**, called from `submit()`.

**Why that was broken:** Spring's `@Transactional` works by proxying the bean. A call from one method to another on the *same instance* never passes through the proxy, so the annotation is silently inert — the method simply joins whatever transaction the caller already had.

Here that is not cosmetic. The stub models an **external system**, and its books must commit independently of ours. Had `recordSubmission` joined the caller's transaction, a caller that rolled back would un-record filings the "IRS" had already accepted — and failure mode B, where the filings are live *precisely because* the caller's view of events is wrong, would become **unrepresentable**. The test suite would be unable to produce the scenario the brief calls the most important line in the document.

---

## D55 — Content hash covers the **plaintext** TIN, never the ciphertext

**File:** `domain/IdempotencyKey.contentHash`

**Why:** TINs are stored under randomized AES-GCM (fresh nonce per write, D19), so the ciphertext changes on every re-encryption and on key rotation. Hashing it would silently change every idempotency key — and filings already live at the IRS would be resubmitted under brand-new keys the server had never seen.

That turns a routine key rotation into a duplicate-generating machine, with no error anywhere and no way to notice until contractors start receiving two 1099s. Hashing the plaintext keeps the key a function of the *filing*, not of how it happens to be encrypted at rest.

---

## D56 — Explicit `TransactionTemplate` everywhere in the transmission path, not `@Transactional`

**Files:** `BatchPlanner`, `BatchDispatcher`, `AckPoller`, `ReconciliationService`, `TransmissionWorker`, `InvariantChecker`, `StubStore`, `FileCommand`

**Why this is a decision and not a style note:** the annotation form failed **seven times** during this build, always the same way and always silently.

Spring applies `@Transactional` through a proxy. A call from one method of a class to another on the *same instance* bypasses that proxy entirely, so the annotation becomes inert and the method runs in whatever transaction the caller had — **or none**. Nearly every service here has methods called both externally and internally (`planOne` from `planAll`, `claimNext` from `drain`, `sweepOrphans` from `reconcile`), which makes the trap unavoidable with annotations.

"Or none" is the dangerous half, and it interacts with D9: without a transaction there is no `set_config`, so `app.current_firm_id()` raises `28000` on the first query touching a firm-scoped table.

That interaction turned out to be a gift. Because firm context is established *at transaction start*, **every missing transaction failed loudly and immediately** rather than silently running non-transactionally and committing each statement independently — which is what would have happened in a system without RLS, and which would have destroyed the atomicity that T1/T2/T3 depend on. The isolation design caught seven latent transaction bugs it was never built to catch.

Explicit templates also make the boundaries visible where they matter most: the reader of `BatchDispatcher` can see that T2 commits, the HTTP call happens outside any transaction, and T3 opens a new one — which is the entire crash-safety argument, and it should not require knowing Spring's proxy semantics to verify.

---

## D57 — Preflight blocks transmission, never the obligation

**File:** `transmission/FilingPlanner.java`

A vendor with no TIN still gets a filing row. It lands in `BLOCKED` with an attention item, counted and visible and assignable.

**Why:** the brief is explicit that a missing TIN *"doesn't remove the filing obligation... never a reason to silently skip the vendor."* The way to guarantee that structurally is to make the filing **exist regardless** and block only the transmission — rather than filtering at selection time, where "we didn't create it" and "we haven't sent it yet" become indistinguishable.

On the real book this produced 3,360 blocked filings against 24,146 ready. Every one is a form somebody owes and nobody can send yet — which is exactly what the morning-after page needs to show, and precisely what a skip-based implementation would have made invisible.

Preflight also rejects what the IRS provably would (`000`-prefix TIN, non-positive amount), because spending one of twenty calls per minute to be told something already known is the most expensive kind of waste in this system.

---

## D14 — No Docker

**Files:** `db/setup.sql`, `application-test.yml`

*Alternative:* Docker Compose for Postgres + Testcontainers for tests (the reflexive default).

**Why:** Postgres 16 already runs natively on this machine, so Docker would buy only reproducibility for the reviewer — and the RLS role split needs a `setup.sql` either way, which Docker merely *runs* rather than removes. Skipping it removes a moving part and makes the test suite faster (no ~10 s container start per run). The required kill-and-resume test doesn't need containers at all: killing a real spawned JVM with `destroyForcibly()` is a *stronger* proof than a container restart. An optional `docker-compose.yml` ships as a reviewer convenience, off the critical path.

---

## D58 — A batch may be released on error only on its **first** attempt

**File:** `transmission/BatchDispatcher.recordOutcome`

`recordOutcome` originally released a batch back to `SEALED` whenever `!failure.serverMayHaveRecorded()`. It now additionally requires `attemptNo == 1`.

**Why — this was a live duplicate-filing path.** `serverMayHaveRecorded()` answers *"did **this call** reach the server?"*. It says nothing about whether an **earlier attempt** did, and on a retry those are entirely different questions:

| Attempt | What happens |
|---|---|
| 1 | Submit → **failure mode B**: the IRS records all 20 filings and returns an error. Batch stays `DISPATCHED`. Correct. |
| 2 | Submit → refused locally (rate limit, connection refused). `serverMayHaveRecorded()` is `false` *for this call* → batch released to `SEALED`, filings to `READY_TO_TRANSMIT` — **while the IRS is still holding them**. |

Those filings would then be re-planned into a **new batch with a new idempotency key**, which the endpoint has never seen and therefore cannot deduplicate. Two 1099s for one vendor.

The class javadoc had claimed *"an error never releases filings — only reconciliation, holding a `StatusResult.Unknown`, may do that."* The code did not match its own stated rule, and nothing caught the gap until a real process was killed mid-run.

**How it was found:** the tier-2 SIGKILL test produced a state that is definitionally impossible — a batch in `SEALED` (which *means* "nothing has left this process") whose 20 filings were already recorded at the endpoint. None of the in-JVM crash-point tests reached it, because they never produce a *second* attempt after a mode-B first one.

`attemptNo == 1` is the only case where "nothing has ever left" is provable. Beyond that the batch stays `DISPATCHED` and reconciliation settles it — one status call, versus a duplicate filing.

---

## D59 — The CLI ignores Spring property arguments

**File:** `cli/CliRunner.withoutSpringProperties`

Spring Boot reads `--some.property=value` into the Environment but leaves it in the argument array, and picocli then rejects the whole invocation as "Unknown options".

**Why it matters beyond tidiness:** it made this impossible —

```
java -jar readiness.jar file --firm=northstar --spring.profiles.active=test
```

— which is exactly how the kill-and-resume test points a child process at the test database, and how anyone would override a stub setting for a demo. The discriminator is a dot in the option name: every picocli option here is kebab-case (`--tax-year`), and Spring keys are dotted (`--irs.stub.hang-on-call-number`), so the namespaces cannot collide.

---

## D60 — The classification SQL is a shared constant, so the differential test cannot drift

**Files:** `DeterminationEngine.CLASSIFICATION_CASE_SQL`, `ClassifierDifferentialIT`

*Alternative:* paste the `CASE` expression into the test.

**Why:** the whole value of a differential oracle is that the two implementations are independent *and* that one of them is the real one. A pasted duplicate keeps passing after someone edits the engine — at which point the test confirms agreement between two things, neither of which is what production runs. Extracting it means the test physically cannot diverge.

**The oracle was verified by mutation rather than assumed.** Moving the reversal check ahead of the card check made it fail immediately and name the offending inputs (`entry=REVERSAL card=true amount=-185841 → java=COUNTED_REVERSAL sql=EXCLUDED_CARD_TPSO`). A property test that has never been seen to fail is a decoration.

The test also asserts **coverage before agreement**: if the generator happened to produce only ordinary payments, agreement would be trivially true and "20,000 cases passed" would be a misleading thing to report.

---

## D61 — Invariants are parameterised by configuration, not by literals

**File:** `InvariantChecker` → I5

I5 originally hardcoded "no rolling **60-second** window contains more than **20** calls".

**Why that was wrong in both directions:** the test profile shortens `irs.rate.window` to 500 ms so runs finish quickly, which makes 21 calls in 60 real seconds entirely correct — and I5 reported a violation that did not exist. Symmetrically, raising `irs.rate.limit` to 30 in production would leave I5 asserting 20 and failing every run for no reason.

An invariant should express the **policy**, not a snapshot of one configuration of it. I5 now reads both values from the same properties the limiter uses, so the assertion and the enforcement can never disagree about what the budget is.

---

## D62 — The morning-after page is derived, never stored

**File:** `V8__dashboard_views.sql`

*Alternatives:* a `client_status` rollup table maintained by the filing pass; a materialized view refreshed on a schedule.

**Why a plain view:** the failure this page must not have is a staff member trusting a stale figure at 7 a.m. and concluding a client is filed when it is not. A materialized view is **stale by construction** — that is its defining property, and it is the one property this page cannot have. A rollup table is worse in a different way: it can be forgotten by a writer, and the forgetting is silent.

A view cannot go stale, cannot be forgotten, and cannot survive the condition that produced it. If a poll succeeds at 06:59, the exception is gone at 07:00 with no reconciliation job in between — and `DashboardIT.resolvingTheConditionRemovesTheRow` asserts exactly that.

Measured cost of `v_client_status`: **16 ms** over the pre-D91 corpus (10,580 filings) and **9–22 ms** over the current one (27,506 filings). It is flat in corpus size, which is the expected shape — the aggregate collapses to one row per client per year, so 250 rows come back either way and only the scan underneath grows.

The **whole page** — all three queries inside the one snapshot — is **86–113 ms across five warm runs** against a 200 ms budget. The dominant term is not this view but `v_exception`, whose second UNION branch anti-joins every filing for the firm against the open attention items to find determination exceptions nobody has raised an item for. That branch is most of the total and is the one to watch as filings grow; `v_client_status` is not.

**The decision does not rest on the timing, and that is the part to lead with.** If the view became too slow tomorrow, the replacement would still not be a materialized view or a scheduled refresh — it would be a rollup table written in the **same transaction as the filing transition**, so it is transactionally consistent and *cannot* be stale. Freshness is a correctness property on this page, not a performance one. The measurement only decides **whether** to keep deriving; it never decides **what** the fallback looks like.

Only the **human annotation** is persisted (`app.exception_ack`), keyed by a stable `dedupe_key` rather than a row id, because derived exceptions have no durable identity. The truth is derived; the note *about* the truth is stored. Nothing that can go stale is ever the thing displayed.

---

## D63 — Every client gets a row for every known year (the cross join)

**File:** `V8__dashboard_views.sql` → `app.v_client_status`

The obvious shape is "group the filings, left join the clients". It has a silent hole: a client with filings in 2024 but none in 2025 produces **no 2025 row at all**, so filtering the page to 2025 makes that client vanish rather than report "nothing to file".

A client disappearing from a completeness page is the worst thing this page can do. Absence reads as *"not my problem"*, and unlike a wrong number there is nothing on screen to question. So the view cross-joins clients against the set of years the system knows about, which comes from `import_run ∪ filing` — both small. Deliberately **not** from `ledger_line`: a `DISTINCT` over a million rows to populate a page header is a cost with no benefit, since a year cannot have ledger rows without an import run that loaded them.

Found by inspection rather than by a failing test, because with a single-year corpus the bug is invisible. `DashboardIT.noClientCanVanishFromACompletenessPage` now pins it.

---

## D64 — Duplicate exceptions are suppressed by an explicit code pairing, never by entity

**File:** `V8__dashboard_views.sql` → `app.v_exception`

A vendor with no usable TIN raises **two** records: a determination exception (`MISSING_TIN` — "we cannot complete this form") and, once the planner reaches it, a filing-level attention item (`VENDOR_MISSING_TIN` — "this filing cannot be transmitted"). Both are correct and both are worth keeping; they are raised by different passes and answer different questions. But to the person reading the page they are **one problem**, and printing 1,218 of them twice is how a page with an honest data model still manages to mislead.

**The first attempt suppressed any determination exception whose vendor already had an open attention item.** Running it against the real corpus caught the flaw: `AMBIGUOUS_VENDOR_IDENTITY` dropped from 23 to 19. Four vendors had *both* a missing TIN and an ambiguous identity, and the blanket rule silently deleted the second problem.

Suppressing a genuine exception is strictly worse than showing a duplicate. So the suppression is now an **enumerated pairing** (`MISSING_TIN`/`MALFORMED_TIN` → `VENDOR_MISSING_TIN`); anything unpaired falls through the `CASE` to `NULL`, never equals the attention type, and is always shown. Counts confirmed: 1,223 · 159 · 213 · **23** · 24.

---

## D65 — The whole page renders in one `REPEATABLE READ` transaction

**File:** `DashboardController.inSnapshot`

The tiles, the exception list, the grouped counts and the client table are five separate queries. Run in five separate transactions during an active filing run, they are five snapshots of five different instants — and the visible symptom is a page showing "fully filed" in a tile while listing a rejection for the same client below it. Each half true; the combination false. At 7 a.m. that is exactly the kind of thing someone acts on.

`REPEATABLE READ` gives all five the same snapshot, and the header states which instant it was. This is the difference between a page that is *accurate* and one that is *coherent*.

Paired with `Cache-Control: no-store` and a 30-second meta refresh. The refresh is crude on purpose: a fetch-and-diff would be nicer and would reintroduce the very bug the isolation level removes.

---

## D66 — The firm comes from the principal; there is no `?firm=` anywhere

**Files:** `FirmUser`, `DashboardController`, `SecurityConfig`

The first version of the controller took `?firm=northstar`. That directly violated the project's own rule, and it is worth recording rather than quietly fixing: **a firm id that can be influenced by the request reduces row-level security to "we remembered to validate it"** — the exact class of control this project set out to replace with a structural one.

The firm now lives on the authenticated principal and nowhere else. The guarantee becomes positional rather than procedural: there is no code path that reads a firm from a request, so there is no code path that can forget to check one. `DashboardIT.theSameUrlIsScopedByThePrincipalAlone` asserts both that two principals get different data from the same URL *and* that the string `firm=` appears nowhere in the rendered output — so a reintroduction is caught even while it still "works".

The visible consequence: `/client/4711` for another firm's client renders **"No client 4711"**, not a permission error. Under RLS the row genuinely does not exist for the session, so the page cannot leak the difference between "another firm's client" and "a typo" — the information never reached the process.

---

## D67 — Authentication needs no `SECURITY DEFINER` function, and could not use one

**Files:** `V9__auth.sql`, `FirmUserDetailsService`

Log-in is the one operation that must run *before* a firm context exists: `app.current_firm_id()` raises `28000` rather than returning NULL, but the context is derived from the user, and finding the user requires a read.

The reflex answer is a `SECURITY DEFINER` function that looks the user up with the owner's privileges. **It would not have worked**, and the reason is instructive: every table in schema `app` is `FORCE ROW LEVEL SECURITY`, which means the owner is subject to its own policies. A definer function running as `readiness_owner` hits the identical `28000`. FORCE is doing exactly what it was added for — including to the escape hatch someone reaches for.

The actual answer needs nothing new: resolve `app.firm` in a system transaction (that table's SELECT policy is deliberately open, per D39, precisely because resolving a firm is what must precede a firm context), then read `app.app_user` inside `FirmContext.runAs`. A firm's staff list stays firm-scoped, there is **no `SECURITY DEFINER` function anywhere in the schema**, and the login path exercises the same tenancy machinery as every other read instead of stepping around it.

The login identifier is `username@firm-slug` because `app_user_name_uk` is per-firm: two firms may each employ a Sam, and a globally-unique login name would quietly make one firm's namespace depend on another's.

---

## D68 — Roles split on irreversibility, and isolation is not a role concern

**File:** `SecurityConfig`

`PREPARER` reads the dashboard, reads a client's explanation, annotates an exception. `FIRM_ADMIN` additionally holds what cannot be taken back: starting a filing run, forcing a state transition, changing run configuration, reading the audit log.

The split is by **consequence, not seniority**. "Can send a form to the IRS that cannot be unsent" is a different privilege from "can look at what happened last night"; a role model organised around job titles puts them on the same side of the line.

Nothing in `SecurityConfig` mentions firms. Both roles are firm-scoped by the same mechanism as everything else, and a `FIRM_ADMIN` has no more cross-firm reach than a `PREPARER`: none. **If tenancy were expressed as authorities, "admin" would eventually come to mean "sees everything"** — which is the failure this whole project is arranged to make impossible. Authorization answers "may this person do this"; RLS answers "whose rows are these"; the two never have to agree about anything.

---

## D69 — The exception list is capped, and says so

**File:** `DashboardController.EXCEPTION_ROW_CAP`

Rendering all 1,223 missing-TIN rows adds roughly a megabyte of HTML to say something the grouped row above says in one line. The cap is 200, and it is safe because the list is **ordered by severity**: the rare, dangerous items (reconciliation discrepancies, rate-budget breaches) are always above it, and what gets cut is the tail of bulk paperwork whose complete count is already stated.

The part that matters is that the page **says when it stopped**. A list that silently truncates is the same failure as a stale rollup: it reads as complete and is not. `DashboardIT.truncationIsStatedRatherThanInferred` pins it.

No pagination anywhere else. 500 client rows is ~60 KB and staff want Ctrl-F; pagination hides the one client they came in for behind a control they have to discover. The filter box is additive — it narrows, it never conceals.

---

## D70 — `bench` exits non-zero, and prints the work done beside every time

**File:** `cli/BenchCommand.java`

*Alternative:* a benchmark that prints a table and always succeeds.

**Why:** a performance claim in a README is a claim a reviewer has to take on trust. A benchmark
that can **fail** is a test, and it can go in CI where a regression is caught by the person who
caused it rather than in February.

The second half matters as much as the first. Every row prints the work done next to the time,
because *"import: 29 s"* against an already-loaded corpus measures the **no-op merge path** and
would be a misleading thing to quote. The row counts make a warm run obvious, and
`--require-cold` turns it into a hard failure — a silently warm benchmark is worse than no
benchmark, because it manufactures confidence.

Verified in both directions: the real run passes at 29.7 s / 22.4 s / 89 ms, and
`--determine-sla-ms=100` produces `[FAIL]` and **exit 1**.

The incremental row is the one not asked for by the brief and the one that matters most
operationally: a revised export at 2 a.m. must not cost a full rescan, or the whole incremental
design is decorative. Measured floor with nothing dirty: **89 ms**.

---

## D71 — `reconcile` is separable from `file`

**File:** `cli/ReconcileCommand.java`

`file` already reconciles before it plans, so the normal path needs this only as a component.

**Why separate it:** reconciliation is the thing an operator wants to run **alone**. After a
crash the first question is "what is outstanding", and answering it should not require also
starting a filing run that immediately begins spending the rate budget on new submissions. Under
a 20-call minute, "settle the ambiguous batches and stop" is a real operational need, and
`--dry-run` answers the question for zero calls.

It also prints the sentence a reader needs rather than a state name: *"N batches are DISPATCHED:
we committed the intent to send them and never learned the outcome."*

---

## D72 — Audit hardening belongs in `V10`, not in the repeatable migration

**Files:** `V10__audit.sql`, `R__grants_and_rls.sql`

**This was a bug before it was a decision, and it failed silently.**

The append-only grants and policies were originally in `R__grants_and_rls.sql`, guarded on the
table existing. **They never ran.** A repeatable migration re-executes only when *its own*
checksum changes — so `V10` created `app.audit_event` in one migrate, `R__` was unchanged and did
not re-run, and the table sat there with the generic `firm_isolation` policy and full DML grants.
Nothing failed. The append-only guarantee was simply absent, and `RlsGuard` passed because a
policy did exist.

Caught by checking `pg_policies` directly rather than by trusting that the file had run.

**The general rule:** a repeatable migration is the right place for a **sweep over whatever
exists**, and the wrong place for hardening that one specific table needs. "Runs eventually, if
someone edits an unrelated file" is not a guarantee. Table-specific policy belongs with the
`CREATE TABLE`, where it is applied once, at the moment the table comes into being.

---

## D73 — Audit events buffer to commit, except on the failure path

**File:** `audit/AuditService.java`

An event describes something that **happened**. If the transaction that did it rolls back, it did
not happen — and an audit log recording it would be **worse** than one that missed it, because a
false entry in the one record that exists to be trusted has nothing downstream to contradict it.

So `record()` buffers into the current transaction and flushes at `beforeCommit`. Two benefits:
the event and the work it describes are atomic in both directions, and the chain-head row lock is
taken once at the very end and held for microseconds — rather than at the start of a filing run
that then holds it for twenty seconds and serialises every other writer in the firm behind it.

`recordSurvivingRollback()` is the deliberate exception, on `REQUIRES_NEW`. "A person tried to
force a transition and it was refused" is precisely the entry whose whole value is that it exists
after something went wrong.

**Two bugs found here, both instructive:**

1. `appendInCurrentTransaction` called `events.clear()` on its argument, which is
   `List.of(event)` — immutable — on the non-transactional path. `UnsupportedOperationException`,
   swallowed into a stack trace that looked like a Spring startup problem. Ownership of the
   buffer now sits with the buffer.
2. **Every chain verified as BROKEN.** The writer hashed Jackson's output; the verifier hashed
   `detail::text` as Postgres returned it. `jsonb` is a *parsed* representation, not the text
   that was inserted — it sorts keys, drops duplicates and renders with its own spacing. Both
   sides now round-trip through the same serialiser, with `ORDER_MAP_ENTRIES_BY_KEYS` so nesting
   is canonical too. **The failure mode of getting this wrong is the worst kind: a verifier that
   cries wolf trains people to ignore it.**

---

## D74 — The audit hash is length-prefixed, and excludes the timestamp

**File:** `audit/AuditService.chain`

`sha256(prev_hash ‖ firm ‖ seq ‖ actor ‖ role ‖ action ‖ type ‖ id ‖ detail)`, every field
length-prefixed with a presence byte.

**Why length prefixes:** without them, `("ab", "c")` and `("a", "bc")` hash identically, so two
different events could be swapped for one another without breaking the chain — and that swap is
exactly what an attacker would want. The presence byte separates a null from an empty string for
the same reason.

**Why `occurred_at` is excluded:** it is a server clock value read at insert, and including it
would require a verifier to reproduce the database's timestamp formatting exactly, in Java,
forever. A changed timestamp is detectable anyway — it cannot be changed without an `UPDATE`, and
`UPDATE` is denied by a revoked grant, by the absence of a policy, and by a trigger.

**Why the head is a separate table:** deleting the tail of the chain leaves every *remaining* link
valid. Only the head knows how long the chain was supposed to be — which is also why `--pin`
writes it outside the database, and why the write-up says plainly that a hash chain is
tamper-*evident*, not tamper-*proof*.

---

## D75 — `@ConditionalOnDatabase`, because `@ConditionalOnProperty` is not repeatable

**File:** `config/ConditionalOnDatabase.java`

`seed` had been broken and nothing noticed. It runs with `readiness.database.enabled=false` and
no `DataSource`, but the IRS stub's beans are selected by `irs.client`, so Spring tried to build
a `StubStore` and failed on a missing `JdbcTemplate` — in a command that touches no database at
all.

The fix could not be a second `@ConditionalOnProperty` on the same class, because the annotation
is not repeatable. Hence a meta-annotation, which composes.

Deliberately still a **property** condition and not `@ConditionalOnBean`: bean conditions on a
component-scanned class are evaluated before auto-configuration has created the `DataSource`, so
they silently evaluate false and remove the bean with no error. That is exactly how `RlsGuard`
was silently excluded once already (D36) — the most dangerous bug in this project's history,
because a guard that can be silently disabled manufactures false confidence.

**Found by running the whole pipeline from a dropped schema** rather than from the state the last
run left behind. Worth recording as a process lesson, not just a code one.

---

## D76 — Stub-side invariants must restate the firm scoping by hand

**File:** `transmission/InvariantChecker` → I1, I2, I3, I5

I2 asserts "nothing is recorded at the IRS that our system never marked as sent" — the mode-B
leak assertion, and the one to put on screen.

It joined the RLS-scoped `app.filing` to the deliberately **unscoped** `irs_stub.recorded_filing`.
The stub's schema stands in for an external system, so it carries no `firm_id` on
`recorded_filing` and no row-level security — which is correct, and is what makes it a genuine
oracle rather than a mirror of our beliefs.

The first time **both firms transmitted in the same database**, firm 2's check saw firm 1's 692
recorded filings, found no matching firm-2 filing, and reported every one of them as a leaked
filing. **692 false violations**, on the single most important invariant in the project.

Fixed by joining through `irs_stub.submission.firm_id`, which does carry the firm. I1, I3 and I5
had the same latent shape and were scoped at the same time.

**The lesson generalises past this query:** whenever an RLS-scoped table is joined to an unscoped
one, the scoping has to be restated by hand on the unscoped side, because the mechanism that
usually does it silently stops at the schema boundary. That is the price of making the stub a
genuinely separate system, and it is worth paying — but it has to be paid explicitly, every time.

**And a process lesson:** this bug was invisible for the entire project because every previous
run exercised one firm. It appeared the first time the full pipeline ran end-to-end across both.
Multi-tenancy bugs need a second tenant that actually does work, not just a second tenant that
exists.

---

## D77 — The six Part 2 cases are proved through the whole pipeline, not against inserted rows

**File:** `DeterminationCasesIT`

**This test did not exist, and both documents claimed it did.** The write-up asserted "each case
has a focused test asserting the canonical instance, plus an aggregate assertion over all ~25
plantings. All nine pass." Nothing in the suite backed that. Found by auditing the documentation
against `find src/test`, which is a check worth making a habit.

*Alternative:* insert `vendor_determination` rows by hand and assert on them.

**Why end-to-end instead:** these cases are about what happens to a **CSV file**. The dialect has
to parse it, the rejection sink has to leave it alone, the vendor resolver has to group it, and
the classifier has to decide it. Every one of those is a place the case could break, and a test
that writes the answer into the table proves the assertion rather than the system.

So it seeds a corpus, imports it exactly as the CLI would, determines it, and asserts against the
`fixtures.json` the generator published **out of band**. Nothing in the CSV marks a row as a
fixture, so the pipeline cannot treat these rows specially even by accident &mdash; which is the
property that makes the assertion mean anything.

A 40-client, 20k-row corpus still plants all ten case types 25 times each, so the whole thing
costs about a second of seeding and ten of importing.

**Canonical instance and aggregate, deliberately both.** Each case asserts its canonical planting
field by field, because that is the level at which a rule is either right or wrong. Then it
asserts the same holds across all 25 plantings: one passing instance can be a coincidence of that
client's other data; twenty-five spread over two firms and hundreds of unrelated vendors cannot.

**Two failures on the first run were both in the test, and one was informative.** The lookup
matched vendors by display name, and the generator draws names from a finite word list &mdash; so
across 40 clients a planted "Fairview Drywall" shares its name with an unrelated organic vendor
and the lookup returned two rows. Now disambiguated by `tin_last4`, which is plaintext and
independent of the blind-index derivation, so the test still does not use the vendor-key
derivation it is testing.

---

## D78 — The client page hid every out-of-year payment

**Files:** `DashboardController.client`, `templates/client.html`

Found by writing D77's test, and it is the exact failure the whole page is built to avoid.

`payment_determination` stores each row under the **ledger line's** year, not the year being
determined &mdash; so a payment dated 2024 is filed as 2024 even when it was classified during the
2025 run. The client page filtered `pd.tax_year = ?`, and therefore dropped every out-of-year
payment.

The visible symptom was **an explanation that did not reconcile**: the vendor's subtraction showed
*"less payments dated outside the tax year: −$430.00"* and then listed nothing accounting for the
$430. A reader could only conclude those payments were never imported &mdash; which is precisely
the confusion the design note in `DeterminationEngine` warns about, arrived at from the other
direction.

The engine was right all along; 29,048 out-of-year rows are classified
`EXCLUDED_OUT_OF_TAX_YEAR` and 6,419 vendor determinations carry a non-zero `out_of_year_cents`.
Only the page was wrong.

Scoping by client and `vendor_key` alone is correct: a full determination overwrites
`payment_determination` in place, so there is exactly one row per ledger line, and every row
carrying that `vendor_key` is that vendor's evidence. The year is now shown as a badge on the
row, because an out-of-year date in a list of in-year dates is otherwise indistinguishable at a
glance from a typo.

**The general lesson:** a derived figure and the evidence behind it must be fetched with the same
predicate. Two queries with different filters against the same fact is how a page ends up
disagreeing with itself while every individual query is correct.

---

## D79 — Architecture rules, because a boundary you have to remember is not a boundary

**File:** `ArchitectureTest`

The README claimed "an ArchUnit rule forbids any reference to the irs_stub schema outside the stub
package". **There was no ArchUnit test.** The dependency was in `pom.xml` and the rule existed
only in prose.

Nine rules now, each corresponding to a claim made somewhere in the documentation: the stub seam,
the SPI carrying no framework types, firm context never read ad hoc, web transactions always
named, `@Transactional` banned, money never a float, `Tin` never a record, the domain never
depending on the page, and the seed generator never depending on Spring or JDBC.

**Two rules failed on first run, and the two failures needed opposite treatment.**

*The float rule was wrong.* It banned all doubles in three packages and caught seven fields, every
one of them legitimate: `maxRejectionRate` (0.05), `backoffMultiplier` (2.0), `reservedShare`
(0.20) and the stub's three failure rates. Those are ratios, not amounts. A rule that fires on
correct code gets suppressed, and a suppressed rule protects nothing &mdash; so it is now scoped
by name to fields that actually hold money, and documented as a reminder rather than the
guarantee. The real guarantee is that money can only be wrong if it is **stored** wrong, and
`FirmIsolationIT` already greps `information_schema.columns` for float money columns.

*The `@Transactional` rule was right, and found eight.* Three were application-layer
(`BookChecksum.snapshot`, `clearDirtyMarks`, `FilingPlanner.planFilings`) and contradicted D56
outright. They happened to work, because every caller invokes them from outside the bean &mdash;
which is exactly the fragility D56 objected to, since whether the annotation does anything depends
on the call site rather than on the code. Converted to explicit `TransactionTemplate`.

The other five are in `StubStore` and are **exempt on purpose, by the same argument reaching the
opposite conclusion**. That class exists as a separate bean *specifically* so the proxy applies and
`REQUIRES_NEW` takes effect, because the stub models an external system whose books must commit
independently of ours. If its writes joined the caller's transaction, a caller that rolled back
would un-record filings the "IRS" had already accepted &mdash; and failure mode B, where the
filings are live precisely *because* our view of events is wrong, would become unrepresentable.
The ban is about self-invocation and about naming; the stub has neither problem.

---

## D80 — "Absent" must mean these rows, not this table

**File:** `TransmissionTestBase.seedClientsIfAbsent`

The transmission fixtures were seeded only when `select count(*) from app.client` returned zero.
That passed for the entire project and broke the moment `DeterminationCasesIT` imported a real
corpus: the table was no longer empty, the three `T-` clients were never recreated, and six
transmission tests failed looking up client `T-1`.

Now an unconditional upsert of the specific rows. **A reset that checks whether the table is empty
is making a statement about other tests' data**, which is the coupling a reset exists to remove.
The same shape appeared twice more in the same pass &mdash; `DashboardIT` asserted the exception
page was empty (true only while nothing else had imported anything) and asserted a total appeared
in a single table cell (true only while every exception shared one code). All three are the same
mistake: an assertion about the world rather than about the thing under test.

---

## D81 — The retry path ignored `reconcile-strategy`, and produced 21 duplicate filings

**Files:** `TransmissionWorker.shouldAsk`, `NonDedupingEndpointIT`

**The most serious bug found in this project**, and it was found by writing a test for a claim
the README already made.

The README said: *"The design does **not depend** on server-side deduplication. `STATUS_FIRST`
exists so a non-deduping endpoint is survivable, and a test proves it with
`idempotent-replay=false`."* There was no such test. When one was written, it produced **21
duplicate filings at the IRS** — one for every filing in the run, under the same idempotency key
and the same generation.

### What was wrong

`irs.reconcile-strategy` was read by `ReconciliationService` and by nothing else. The ordinary
in-flight retry path made its own decision, in one line:

```java
boolean isPoll() { return "SUBMITTED".equals(state); }   // DISPATCHED -> re-dispatch
```

So a `DISPATCHED` batch — one where we committed the intent to send and never learned the
outcome — was always **re-sent under its existing key**, whatever the configuration said. That is
`REDISPATCH_SAME_KEY` behaviour hard-wired into the worker.

Against the default stub it is invisible, because the default stub deduplicates: the resend is
recognised and replayed. Turn that off, and each retry records the filings again. The stub's call
log showed it plainly: **six SUBMIT calls per idempotency key, two submission rows, forty-two
recorded filings.**

### Why it matters more than the line count suggests

The system was **silently depending on a property of somebody else's server** — the exact
dependency the design claims to avoid, and the reason `STATUS_FIRST` was written in the first
place. Every other part of the transmission design says *only evidence from the IRS moves a
filing*. A blind re-send is not evidence; it is a bet that the endpoint will catch it.

It also would not have shown up in production until the endpoint's behaviour changed — and the
symptom would have been duplicate 1099s already filed with the federal government.

### The fix

`TransmissionWorker` now reads the same setting, and the decision is stated where it is made:

- `SUBMITTED` → poll. There is a receipt, so there is a specific thing to ask about.
- `DISPATCHED` under `REDISPATCH_SAME_KEY` → re-send. One call, and a receipt resolves it —
  safe *because the endpoint deduplicates*.
- `DISPATCHED` under `STATUS_FIRST` → **ask by key and send nothing.** One call, and the answer is
  proof rather than a guess. The only safe move when the endpoint keeps no idempotency store.

Duplicates went 21 → 0. `AckPoller.poll` already handled a receipt-less batch, so no new
machinery was needed; the information simply was not reaching the decision.

### The lesson worth keeping

A configuration switch that no test ever flips documents an intention, not a capability. This one
had a comment in `application.yml` — *"set false to prove we survive a non-deduping endpoint"* —
that had never once been acted on.

---

## D82 — No `DISCARD ALL`, proved rather than asserted

**File:** `ConnectionPoolIsolationIT`

The README claimed *"a test pins the pool to size 1 and asserts the setting is NULL on
re-checkout."* There was no such test either.

The claim it backs is a real decision: there is deliberately no `DISCARD ALL`, no reset SQL and
no `connectionInitSql`, because adding a belt-and-braces reset would say the primary mechanism
is not trusted — and a reset that silently papers over a leak removes the only signal that
something is wrong.

That is only defensible if `set_config(..., is_local => true)` genuinely unwinds. Three tests now
check it, and the pool is pinned to **one connection** so "the next borrower" is guaranteed to be
the same physical session. At the default pool size the second transaction would probably get a
different connection and the test would pass without ever exercising reuse — a green tick for a
check that never ran.

The rollback case is the one worth having: a mechanism that cleaned up only on the happy path
would leak on exactly the requests that failed, and failures are when a connection is most likely
to be recycled in a hurry.

---

## D83 — "Occasionally never" is a non-event, and nothing counts down

**File:** `NeverAcknowledgedIT`

The brief says acknowledgments arrive "minutes to hours later — occasionally never", and that the
design shouldn't care which. The way most designs fail that is by **counting**: a maximum poll
count, a give-up-after-N, a terminal `ABANDONED`. Any of those turns "never" into a cliff, and
the filing falls off it quietly.

This system caps the interval and never the count. `ack-never-rate=1.0` makes never certain
rather than occasional, and the test asserts the four things that together mean the filing is
still being handled: it stays in `SUBMITTED_UNACKNOWLEDGED` (which is *filed on time*, not
failed), no outcome is invented, an attention item names the wait, and **the batch is still
scheduled**. The last is the one a give-up implementation fails.

Also asserts invariant I4 directly — every filing terminal, scheduled, or flagged — because a
count can match perfectly while a filing sits in a state nobody will act on.

---

## D84 — Five of eight crash points had never been armed

**Files:** `EveryCrashPointIT`, `docs/CRASH_POINTS.md`

`CrashHooks.CrashPoint` defines eight points and production code fires all eight on every run.
**Three had tests.** The other five were hooks that existed, were called constantly, and had never
once been armed — which is strictly worse than not having them, because their presence in the enum
reads as coverage.

The write-up also cited "twelve crash points (C0–C11)" **by number**, and no enumeration existed
anywhere. It was a plan-mode aspiration that never got written down.

Both are now real. The enumeration lists what is durably true at each point, what the endpoint
actually holds, how it recovers, and which test covers it. Writing it out is what made the C6/C7
pairing legible: a crash after the endpoint recorded, and failure mode B, produce **byte-identical
durable state** — and the table is where that stops being a claim and becomes two adjacent rows
with the same recovery column.

The last scenario runs **all eight in sequence against one book**, so each recovery's output is
the next crash's input. Individually each point is a hypothesis about one moment; in sequence it
is a question about the system, and much closer to what a bad night looks like.

---

## D85 — The batch lease is the one timing that is not configuration

**File:** `EveryCrashPointIT.crashDuringAckApply`

Found while testing the ack-apply crash point: after the crash, six drains applied nothing and all
nine filings stayed unacknowledged. Not a bug — the crashed worker still held the batch's
**lease**, and that is the lease doing its job. A process that stopped responding might not
actually be dead, so another worker must not seize its batch the same instant.

But the duration is **hardcoded at two minutes** in the claim query, and it is the only timing in
the system that is not configuration. The test profile scales the rate window to 500 ms and the
poll interval to 20 ms so the real limiter and real backoff still run, just faster; the lease
cannot be scaled the same way. **That is precisely why this path had no test** — it could not be
reached without either waiting two minutes or editing production code.

The test expires the lease explicitly instead, which moves the *data* rather than mocking the
clock, so the real claim query with its real predicate still decides the batch is available again.
Making the lease configurable is a small change and is recorded as a known limitation rather than
done: changing it without a reason is how a system accumulates knobs.

---

## D86 — The TIN protections had no unit test

**File:** `TinProtectionTest`

The most sensitive column in the schema — for a sole proprietor the TIN *is* their SSN — and every
claim about it was asserted only in prose. Ten tests now, with no Spring context and no database,
so they run in milliseconds: a security check that only runs inside a slow integration suite is a
check people learn to skip.

Four properties, and *what* is tested matters as much as that it is:

- **`toString()` cannot leak**, checked through the four paths the leak actually takes —
  concatenation, a format placeholder, a collection's `toString`, and an exception message. Not
  "the method returns a masked string", because that is not how it happens.
- **The blind index is firm-scoped.** The same contractor at two firms produces different indexes,
  so a leaked column cannot be joined across firms to reveal a commercial relationship neither
  firm disclosed.
- **The ciphertext is bound to its row.** Moving it to another vendor, client or firm fails to
  decrypt rather than silently attaching one contractor's TIN to another's name — a wrong tax form
  that would look entirely plausible on screen.
- **The two keys do different jobs.** A service configured with the blind-index key as its
  encryption key cannot open the ciphertext. That is the one that matters most, because the
  grouping key is the more exposed of the two: it must be present for every `GROUP BY` over a
  million rows.

The masking converter test also asserts it does **not** mangle ordinary numbers. A backstop that
ate row counts and timings would be deleted within a week, which is the real failure mode for
defences that are noisy.

---

## D87 — The documented CSV schema did not actually import

**File:** `ledger/PaymentMethod.fromRaw`, `RowNormalizerTest`

`README`'s CSV schema lists payment methods in snake_case — `credit_card`, `paypal`,
`ach`. The synonym map holds `"credit card"`, and `fromRaw` normalised case and collapsed
whitespace but **did not touch underscores**. So `credit_card` matched nothing, fell through to
`UNKNOWN`, and defaulted to non-card.

A file written exactly as this project documents therefore had its **card payments counted toward
the $600 threshold**, producing forms for vendors whose reportable total should have excluded
them. The default direction is the safe one — an unknown method counts, so the failure over-files
rather than suppressing an obligation — but it is still wrong, and nothing anywhere would have
said so.

Invisible against our own corpus, because the generator writes the canonical display names
(`Credit card`, `Third-party network`), which lowercase straight into the map. Only a file written
by somebody following the documentation would have hit it.

**Hyphens are deliberately not folded.** `third-party network` and `p-card` are synonyms in their
own right; collapsing hyphens to spaces would stop both matching and trade one silent
misclassification for two.

Found by writing the first test of the rejection taxonomy. The pattern from the earlier passes
holds: the gap was between the prose and the code, and only a test that consulted both could see
it.

---

## D88 — The rejection/exception line, enumerated

**File:** `RowNormalizerTest`

The distinction the brief is probing had no focused test at all. The whole ingest package —
dialects, normaliser, rejection taxonomy — was covered only incidentally, by importing a corpus
end to end and observing that the totals came out right.

That is weak evidence for this particular property, because a corpus exercises the *common* path.
The interesting cases are the boundaries, and each one is a decision that fails silently in a
different direction:

| Rejected | Why it cannot be represented |
|---|---|
| `UNPARSEABLE_DATE` | The payment date decides the tax year. A guessed date silently moves a payment between filing years. |
| `UNPARSEABLE_AMOUNT` | No sensible default exists. |
| `SUB_CENT_AMOUNT` | Rounding is worse than rejecting: a half-cent absorbed here is a discrepancy against the client's books that nobody can trace to a row. |
| `UNSUPPORTED_CURRENCY` | Converting needs a rate, a date and a policy this system does not have. |
| `RAGGED_ROW` | Checked **first**, before any field is read — a misaligned row parses fine field by field and just puts the date where the amount belongs. |
| `MISSING_CLIENT_REF` / `UNKNOWN_CLIENT_REF` | A payment belonging to no client cannot be filed by anyone, and attaching it to another client puts one client's spend on another's 1099. |
| `UNIDENTIFIABLE_VENDOR` | The one case where a missing TIN contributes — and only because the name is missing too. With neither there is no identity to attach the payment to, and no exception a human could act on: nobody to chase for a W-9. |

And the mirror set, which must **not** reject: a blank TIN (rejecting it deletes a filing
obligation), a malformed TIN (kept and flagged, never repaired), an unknown payment method
(defaults non-card, the direction that errs toward filing), an out-of-year date, and a negative
amount.

---

## D89 — Determinism is a property of the corpus, tested as one

**File:** `SeedDeterminismTest`

Every fixture assertion in this project is written against `fixtures.json`. If the same seed
produced even slightly different data on another machine, those assertions would be checking a
corpus nobody has ever seen — and the suite would be green while proving nothing.

The claim was documented and untested. Seven tests now, and the choice of what to check is the
point: the failure modes are all boring, all easy to reintroduce, and none of them show up in a
single run.

- **Same seed twice → byte-identical**, compared by per-file hash so a failure names the file
  rather than dumping a megabyte of CSV.
- **Different seed → no file identical.** Not redundant: a generator that ignored the seed would
  pass the first test perfectly while being useless.
- **LF endings, no BOM, no comma-decimals.** `System.lineSeparator()` alone makes Windows and CI
  disagree on every line; `String.format("%.2f", …)` emits `1178,05` on a comma-locale machine.
- **Every manifest checksum matches the bytes on disk** — the manifest is written after the
  writers close, and a checksum computed over an unflushed buffer would fail at the importer
  rather than here.
- **Nothing in the CSV marks a planted row.** If it did, the pipeline could treat fixtures
  specially, and every Part 2 case test would be proving something about a code path only
  fixtures take.

---

## D90 — A near-full disk doubles every phase, and `bench` caught it

**Evidence:** a clean end-to-end run on current code, PostgreSQL volume at 97% (11 GB of 270 GB).

Re-running the full pipeline to refresh the documented timings produced an import of **137.1 s
against a 120 s budget** — where the same pipeline on the same machine had taken 62.0 s. Every
phase roughly doubled, including CPU-bound parsing (8.4 → 13.6 s) and the tombstone sweep
(4.2 → 16.3 s), which is what identified it as environmental rather than a regression in any one
code path.

Ruled out with evidence rather than assumed: database bloat (the schema was dropped and rebuilt
from migrations; 724 MB fresh), autovacuum contention (zero active backends), and the corpus
volume (27 GB free, 375 MB of exports).

**Both readings are documented rather than the convenient one.** The headline figures stay as the
ample-headroom run, because that is what a reviewer on an ordinary machine reproduces; the
degraded run is recorded beside it as an operational fact, with the machine state for each.
Quoting only the fast numbers would be flattering and unreproducible; quoting only the slow ones
would describe this laptop's disk rather than the system.

**Two things this vindicated:**

- `bench` exiting non-zero is the whole point of it. It reported `SLA MISSED` rather than printing
  137 s and moving on, which is the difference between a benchmark and a report.
- **Correctness was untouched.** Identical on both runs: idempotency 7/7 per firm, revision deltas
  exact (35/22/16 and 55/35/22), all 16 invariants holding across both firms after six filing
  rounds, both audit chains intact. A full disk moves the budgets and nothing else — which is the
  right sensitivity, because a missed SLA is a scheduling problem and a missed invariant is a
  duplicate 1099.

---

## D91 — Vendor totals were not physically possible, and the demo is what caught it

**Files:** `SeedConfig`, `SeedGenerator.buildVendors`, the share curve in `generateClientRows`

Walking the client detail page on screen surfaced a contractor billing one medical practice
**$2,537,234.75 across 3,461 payments in a year**. Every rule applied to that vendor was correct —
the card exclusion, the reversal netting, the threshold test all worked — but the vendor was not a
thing that exists, and an interviewer looking at that screen would have stopped listening to the
tax logic.

Two independent causes, and neither was in the determination code:

**Vendor count did not scale with payment volume.** It was drawn log-normally around a fixed mean
of 40, independent of the client's row budget. So a client with 15,000 payments a year still dealt
with about forty contractors. Now it scales at roughly one contractor per 18 payments, with the
same log-normal jitter applied around the scaled centre rather than a constant.

**The Zipf head was too heavy.** `share[v] = 1000 / (v + 1)` is a pure harmonic series, and the
top vendor takes `1/H(n)` of everything — about 23% of a 40-vendor client's entire year. Softened
to `1/(v+1)^0.65`, which keeps the head clearly the busiest supplier and the tail paid once or
twice, without being absurd at the top. A hard cap of 260 payments per vendor per year — about one
per working day — backstops an unlucky draw at a very large client.

Measured across the corpus afterwards:

| | Before | After |
|---|---|---|
| Payments per vendor, median | ~40 | **9** |
| p90 / p99 | — | **29 / 104** |
| Maximum | **3,461** | **271** |
| Annual total, median | — | **$6,365** |
| Maximum | **$2,537,234** | **$225,018** |

Row count is unchanged at 999,757, so the brief's ~1M requirement still holds, and all 111 tests
pass — including the nine planted cases, which still land on their exact expected figures.

**The lesson is about verification, not distributions.** Every automated check passed against the
old corpus, because every check tested a *rule*. Nothing tested whether the data those rules ran
on was plausible, and no assertion I would plausibly have written would have caught it. It took
looking at one screen the way a reviewer would.

It also invalidated a claim in the write-up's "where does this break" answer, which had used the
old corpus to argue batches were nearly empty. With realistic vendors, mean batch occupancy is
63.5% rather than 6%, so the constraint is narrower than I had described: not that batches go out
mostly empty, but that **every client costs at least one call regardless of size**. That correction
is now stated in the write-up rather than quietly rewritten.
