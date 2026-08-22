# Crash points

Every moment a filing run can die, what is durably true afterwards, what the IRS actually holds
at that instant, and how the system recovers.

This exists because "it recovers correctly" is not a claim anyone should accept without seeing
the enumeration. The interesting part is not that each row recovers — it is that **several rows
are indistinguishable from inside the process, and recover identically**. That is the design,
not a limitation of it.

Eight of these are named points in `CrashHooks.CrashPoint`, fired by production code and armed by
`KillAndResumeIT` and `EveryCrashPointIT`. The remaining four are windows *between* transactions,
where no hook is needed because the recovery is "nothing was in flight".

| # | Where the process dies | Hook | Durably true here | What the IRS holds | Recovery |
|---|---|---|---|---|---|
| **C0** | Before the run starts | — | Determinations only | Nothing | Next run plans normally |
| **C1** | Mid-planning, before the seal commits | `BEFORE_SEAL_COMMIT` | Filings `READY_TO_TRANSMIT`; no batch row | Nothing | Re-plan. Filing ids are UUIDv5 of the business key, so replanning yields the *same* filings, not a fresh set |
| **C2** | After the seal commits | `AFTER_SEAL_COMMIT` | Batch `SEALED`, filings `BATCHED` | Nothing | Dispatch on the next drain. `SEALED` is the one state that permits releasing filings without evidence, because nothing was sent |
| **C3** | Mid-dispatch, before the intent commits | `BEFORE_DISPATCH_COMMIT` | Batch still `SEALED` | Nothing | Re-dispatch. **The rate token rolled back too**, because it is consumed inside this transaction |
| **C4** | After the intent commits, before the call | `AFTER_DISPATCH_COMMIT_BEFORE_CALL` | Batch `DISPATCHED`, filings `SUBMITTED_UNACKNOWLEDGED` | **Nothing** | Reconcile. A status call returns "never seen this key" → void the batch, bump `generation`, re-plan |
| **C5** | During the call | `DURING_CALL` | Batch `DISPATCHED` | **Unknown** | Reconcile. Only an answer from the endpoint moves it |
| **C6** | After the endpoint recorded, before we commit the outcome | `AFTER_CALL_BEFORE_OUTCOME_COMMIT` | Batch `DISPATCHED` | **Recorded** | Reconcile. A status call returns the receipt → resolve forward |
| **C7** | **No crash at all — failure mode B** | — | Batch `DISPATCHED` | **Recorded** | Identical to C6 |
| **C8** | After the receipt is stored, before the first poll | `AFTER_RECEIPT_BEFORE_POLL` | Batch `SUBMITTED` | Recorded | Poll. Not ambiguous — there is a receipt, so there is a specific thing to ask about, and nothing is re-sent |
| **C9** | Part-way through applying acknowledgments | `DURING_ACK_APPLY` | Batch `SUBMITTED`; apply rolled back as a unit | Recorded and acknowledged | Re-poll. The apply skips members that already carry an ack, so it is idempotent. The crashed worker's **lease** holds the batch for up to two minutes first |
| **C10** | After acks applied, before the batch is marked acknowledged | — | Filings terminal, batch `SUBMITTED` | Done | Re-poll; the apply is a no-op and the batch settles |
| **C11** | Between drain iterations | — | Consistent | Consistent | Resume. Every boundary is a committed transaction, so there is no partial state to repair |

## The pair that matters

**C6 and C7 produce byte-identical durable state.** In one the endpoint recorded the filings and
we crashed before learning it; in the other the endpoint recorded them and *told us the call
failed*. From inside the process there is no observation that separates them.

The system does not try. Both map to `SUBMITTED_UNACKNOWLEDGED`, both leave the batch
`DISPATCHED`, both recover by asking the endpoint. Collapsing "recorded but I crashed" and
"recorded but you lied to me" into **one epistemic state with one recovery procedure** is the
goal, not a compromise — and it is why the state is named for what we know rather than for what
happened.

C4 is the mirror image and shows why the recovery has to be proof-carrying rather than optimistic:
it looks *identical* to C5 and C6 in our own tables, and only the endpoint can tell us which one
we are in.

## What each row is protected by

- **C1–C3** rely on transaction boundaries alone. Nothing was sent, so nothing can be duplicated.
- **C4–C7** rely on the **write-ahead barrier**: the intent to send commits *before* a byte goes
  out, so there is no window where the endpoint holds something we have no record of. Invariant
  I2 asserts exactly this, judged against `irs_stub.recorded_filing`.
- **C4–C7** additionally rely on `uq_one_submission_per_epoch` — `unique (firm_id, filing_id,
  filing_generation)`, with **no `WHERE` clause**. Any recovery bug that would resend a live
  filing under the same epoch has to violate this constraint to reach the wire, and cannot.
- **C8–C10** rely on the ack apply being idempotent and atomic: members already carrying an ack
  are skipped, and the whole apply is one transaction.

## Coverage

| Test | Points |
|---|---|
| `KillAndResumeIT` | C2, C4, C6 (mode B + crash) |
| `EveryCrashPointIT` | C1, C3, C5, C8, C9, plus all eight in sequence against one book |
| `ModeBNoCrashIT` | C7 |
| `RealProcessKillIT` | C5 via a real `destroyForcibly()` on a child JVM, with mode B forced |
| `NonDedupingEndpointIT` | C6/C7 against an endpoint that does **not** deduplicate |

C0, C10 and C11 have no hook because they have no failure mode to test: at each, the durable
state is a committed, consistent snapshot and recovery is "carry on".

## An honest note on the lease

`C9` is the one point where recovery is not immediate. A crashed worker still holds the batch's
lease, and another worker will not touch it until that expires — which is the lease doing its job,
since a process that stopped responding might not actually be dead.

The duration is **hardcoded at two minutes** in the claim query. Every other timing in this system
is configuration, and the test profile scales them all down; this one cannot be, which is why this
path had no test until one was written that expires the lease explicitly. Making it configurable
is a small change and is listed under known limitations rather than done, because changing it
without a reason to is how a system accumulates knobs.
