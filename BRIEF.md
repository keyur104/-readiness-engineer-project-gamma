# Readiness Engineer — Build Project

## At a glance

You'll build a small production-grade system: batch 1099-NEC preparation and filing for CPA firms.

- Stack: Ruby on Rails is what we run day to day, so there's a slight preference for it — but strong backend engineering in the stack you know best is what matters. Database and job backend are your call.
- Deliverables: a GitHub repo shared with @seanmcoleman, a short video walkthrough, and a write-up. We run everything locally — no deployment.
- Where this brief is silent, decide and document the assumption. Scoping is part of the job.

## Why this problem

Soraban builds workflow automation for CPA firms. Our industry is seasonal (most of a firm's revenue lands in ~10 weeks), trust-heavy (regulated client data, professional liability), and unforgiving (a mistake is a penalty notice, not a bug report).

Our engineering team splits product work into two roles. Discovery Engineers turn rough problem statements into working prototypes fast. Readiness Engineers take what works — sometimes only just — and make it something we can run for every firm during filing season. This project is for the Readiness Engineer role. It's a compressed version of that job: the hard part isn't the features, it's making them correct under failure.

## The problem

A business that pays a contractor $600 or more during tax year 2025 must issue that contractor a 1099-NEC and file a copy with the IRS. Most businesses don't do this themselves — their CPA firm does, transmitting as the client's authorized agent under credentials issued to the firm. (The threshold changes across years; the rules you need for this project are spelled out in Part 2.)

Work in tax year 2025, filed January 2026. Because January 31, 2026 falls on a Saturday, the operative deadline is Monday, February 2, 2026.

In production: a firm files for thousands of business clients at once, from millions of ledger lines, mostly in the final 72 hours, through one shared transmission channel. The bar: a filing run works unattended overnight on February 1, and the firm can trust what it's looking at the next morning. You'll build at a smaller scale, but every design decision should survive being asked "and what happens to this in that January?"

## Build this

Four parts. Spend at least half your time on Part 3 — it's what we're really evaluating.

---

### Part 1 — Seed data & import

Client exports are CSVs from QuickBooks, Xero, and hand-maintained spreadsheets: a year of accounts-payable activity, recorded however that bookkeeper felt like recording it.

Requirements:

- A seed generator producing roughly 1,000,000 ledger lines across ~500 business clients and 2 firms, deterministic given a seed. You define the CSV schema; each row carries at least a client identifier, vendor name, vendor TIN (may be blank), payment date, amount, and payment method. Include the edge cases from Part 2 plus some malformed and incomplete rows.
- A full export (one firm's worth) imports in well under two minutes. Naive row-at-a-time inserts won't get there — that's the point.
- Importing the same file twice changes nothing. Give us a way to verify that (a checksum task, a test — your call).
- Importing a revised export (the bookkeeper found a missed invoice) updates what changed and nothing else, and re-determination after it doesn't require rescanning everything.
- A malformed row is skipped and reported, never a reason the whole import dies.

---

### Part 2 — Determination

For each business client, determine which vendors require a 1099-NEC — and make it explainable: for any vendor, the system can show which payments counted, which didn't and why, and the total.

The rules you need — no tax background required, apply these:

- A 1099-NEC is required for any vendor paid $600 or more during the tax year for services.
- Vendors are identified by TIN, not by name string. Payments under different spellings of the same vendor's name with one TIN aggregate to one vendor.
- The reportable amount is the net paid for the year — reversals and refunds reduce it.
- "$600 or more" is inclusive: a total of exactly $600.00 requires a form.
- Payments made by credit card or third-party payment networks don't count — the payment processor reports those separately on Form 1099-K. Only the non-card portion counts toward the threshold.
- If backup withholding was taken from a vendor, a 1099-NEC is required regardless of amount, and it reports the withholding.
- A missing TIN doesn't remove the filing obligation. The vendor still requires a form, but it can't transmit cleanly — treat it as an exception for a human to resolve (a W-9 needs collecting), never a reason to silently skip the vendor.

Your generated data must include these six situations, and your system must handle each correctly:

1. The same vendor under three spellings of their name, one TIN.
2. A December payment reversed: gross for the year $800, net $250.
3. A vendor total of exactly $600.00.
4. A vendor with no TIN (the client never collected a W-9).
5. $2,400 paid to a vendor, $1,900 of it by credit card.
6. $400 paid to a vendor, with backup withholding taken.

A full determination pass across the seeded book should complete in under a minute.

---

### Part 3 — Transmission *(the heart of this project)*

Filings go to the IRS in batches. There's no sandbox, so you'll build a stub that behaves like production. The stub itself is small and isn't what we're evaluating — how your system behaves against it is. Make its failure rates, latency, and delays configurable (including zero, for tests); we may swap in our own implementation.

Your stub's behavior:

| Behavior | Specification |
|---|---|
| Submission call | At most 100 filings, all for the same client. Accepts an idempotency key. |
| Success response | Returns a receipt. Says nothing about per-filing acceptance. |
| Failure mode A | 7% of calls fail before anything is recorded. Retrying is safe. |
| Failure mode B | 5% of calls record every filing, then return an error anyway. The filings are live; you never see the receipt. This is the most important line in this document. |
| Status call | Separate call, by receipt or idempotency key. Acknowledgment arrives after a configurable delay (default it to ~10–30 seconds; in production it's minutes to hours, occasionally never — your design shouldn't care which). |
| Acknowledgment | Per filing, not per batch. Individual rejections with reason codes (at minimum: malformed TIN, TIN beginning `000`, non-positive amount). Accepted filings get a record identifier. |
| Rate budget | 20 calls per rolling 60 seconds per firm, shared across all clients and both call types. Excess calls refused. |

Requirements:

- Zero duplicate filings, ever — across crashes, restarts, retries, and especially failure mode B. On restart, reconcile via idempotency keys before submitting anything new.
- An explicit filing state model, documented in the README. Every filing is in exactly one state; "submitted, unacknowledged" is its own state; "we stopped retrying" is not a terminal state — it's something a human gets shown.
- Kill a run mid-batch and resume it. Write the test that proves the recovered state is right. This test is required.
- A submission unacknowledged past a (configurable) threshold is surfaced for a human, while polling continues within budget.
- Never exceed the rate budget.

---

### Part 4 — The morning after

One plain page. A staff member arriving after an overnight run can see, at a glance:

- Per-client status: fully filed / partially filed / awaiting the IRS / needs attention.
- An exception list of everything needing a person, grouped by type: vendor with no TIN, filing rejected (with reason), submission unacknowledged too long, anything else your state model produces.

Functional beats pretty — we're not judging visual design. It just has to be fast and truthful.

---

### Security (kept deliberately small)

- Firm isolation is structural, not conventional. Cross-firm data access should be prevented by the architecture itself so that a forgotten `where` clause fails safe instead of leaking another firm's data. Describe the safeguards you chose and why.
- TINs are sensitive data (for a sole proprietor, the TIN is their SSN).
- Keep appropriate logs to use for security and compliance purposes. No permissions framework — two roles at most.

## Explicitly out of scope

Don't build these. Some appear in the write-up instead.

- Corrections. In reality, fixing an accepted filing means a corrected form to the IRS and the vendor, penalties, and a permanent record alongside the original. Don't build it — answer in the write-up: how would you model corrections, and how do you prevent two people correcting the same filing at once?
- Authentication, account management, notifications — stub or omit.
- Client approval flow — assume filings arrive approved.
- Deployment — we run it locally.
- UI beyond Part 4.

## What to submit

1. Repository — read access to @seanmcoleman. README covers setup, how to seed and import, how to run a filing run, your filing state model, and your CSV schema. Include a task or log output that reports import and determination timings. Assume a normal dev environment and nothing else.
2. Video — 5–10 minutes, screen-sharing the running app and the code. We're more interested in *why* than *what*.
3. Write-up — a file at the repository root:
   - What did you build, and what did you deliberately leave out?
   - How each Part 2 case flows through your implementation.
   - How did you convince yourself this is correct when it gets interrupted?
   - How would you model corrections (see out of scope)?
   - Where does this break? Give us the load, data shape, or timing that takes it down.
   - What would you have asked us, if you could have?

## On AI tools

Use whatever you'd use on the job, including AI — that's how we work. But anything you ship, you own: we'll ask you to extend it, in detail, in a later conversation.
