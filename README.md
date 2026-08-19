# Readiness Engineer — Build Project

This project is shaped like the work itself: a domain with real constraints, a deadline that doesn't move, and failure modes that only appear under production load.

## About Soraban

Soraban builds workflow automation for CPA firms. We're a Series A company selling into an industry that still runs largely on email attachments, PDF checklists, and shared drives.

Our customers range from firms with a handful of preparers to firms with several hundred. The largest carry hundreds of thousands of clients — individuals, partnerships, S corps, trusts — and across our customer base we support low millions of them. Client-to-staff ratios are high, and during filing season a firm is running hundreds of returns in parallel, each blocked on something different.

Three characteristics of the industry shape what we build. It is highly seasonal: most of a firm's annual revenue is earned in roughly ten weeks, so a product that isn't ready by January waits a year. It is trust-heavy: firms carry professional liability for their work and are regulated in how they handle client data, so products that ignore those constraints don't get adopted. And errors are costly in a direct way — a firm that files late or incorrectly receives a penalty notice, not a bug report.

## The Role

Soraban moves quickly. Our Discovery engineers work with the CEO and Head of Product to turn a rough problem statement into working functionality fast — a spec rarely settles whether an idea is right, and something you can actually use usually does. That work answers the product question and leaves the engineering questions open.

The Readiness Engineer answers those: taking something that works — sometimes only just, and sometimes only for the case it was built to show — and making it something we can run for every firm during filing season. It's full stack with the weight on the backend: data modeling and query performance, background processing, logging, operational alerting, concurrency, security, failure recovery, and enough frontend to keep an interface usable when the operation behind it is slow, partial, or interrupted.

Scale is part of it. The harder part is usually everything arriving at once — partial failures, several people working at the same time, outside systems misbehaving, sensitive data moving through, and inputs nothing like what the prototype assumed, in the busiest week of the year.

This project is a small version of that.

## The Problem

A business that pays a contractor above a threshold amount over the year must issue that contractor a 1099-NEC and file a copy with the IRS. Most small businesses don't do this themselves — their CPA firm does it for them. The client sends over a vendor payment export from their books, the firm determines which vendors crossed the threshold, prepares the forms, and transmits them to the IRS as the client's authorized agent, under transmitter credentials issued to that firm.

Work in **tax year 2025**, filed in January 2026. The threshold that year is **$600** — note that it is not $600 in every year — and because January 31, 2026 falls on a Saturday, the operative deadline is **Monday, February 2, 2026**. Both the copy to the vendor and the copy to the IRS are due then.

> Firms file 1099s for their business clients every January. A firm carrying a couple hundred thousand clients, several thousand of them businesses that need this filing done, is preparing and transmitting over two hundred thousand forms inside three weeks, working from tens of millions of ledger lines, using tools built to handle one client at a time. We want to own that workflow. Build the version that survives a real January: a firm's whole book at once, a single transmission channel shared by all of that firm's clients, and nobody available to babysit it at 2am on the deadline.

That's the ask as we'd write it internally. Three things have to work: getting the data in, deciding who needs a form, and getting those forms to the IRS and finding out what happened to them. Around those sits the work of fixing what turns out to be wrong, giving staff a way to handle everything that needs a person, and controlling who can see what.

**Intake.** The client's books are the source of truth, and those books are whatever the client's bookkeeper produced. Exports arrive as CSV out of QuickBooks, out of Xero, out of a spreadsheet someone has maintained by hand since 2014, and they contain the year's entire accounts-payable activity rather than a tidy list of contractors. Amounts, dates, identifiers, and payment methods are each recorded the way that particular bookkeeper felt like recording them, which is not the way the next one did. Plenty of rows are incomplete. Since you're defining the file format, assume we'll take what your generator produces and make it worse before we run it through your importer.

The same file will also be uploaded twice — sometimes because someone double-clicked, sometimes because the first attempt died two-thirds of the way through and someone is retrying. There is a third case, described further down under what makes this hard.

A small client's export is a few hundred lines. A staffing agency or a property manager will send two million, and the firm imports all of them inside the same three weeks. An export is also one of the most sensitive artifacts the firm handles — every vendor's name, identifier, and payment history for a business. Wherever it lands after upload is part of your design.

**Determination.** For each business client, work out which of their vendors requires a 1099-NEC. How you arrive at that is your call, and the data is where it gets difficult — every one of these is sitting in real exports:

- The same vendor appears under three spellings of their name with one TIN.
- The same vendor appears with two different TINs — they incorporated in June.
- A December payment is reversed. Gross for the year is $800, net is $250.
- A vendor's total lands at exactly $600.00.
- A vendor has no TIN at all, because the client never collected a W-9.
- A payment is dated December 31 and clears January 2.
- A vendor was paid $2,400, of which $1,900 went out on a credit card.
- A vendor was paid $400 and had backup withholding taken from it.
- One vendor is paid by forty different clients of the firm, $500 by each.

Decide what the right answer is for each and write down why. Tax background helps but isn't required — the rules are findable, and we're reading whether you went and found them. Several changed recently, and a confident wrong answer is worse than an uncertain right one.

**Transmission.** Filings go out in batches to the IRS. Each firm transmits under its own credentials, which means one channel and one rate budget shared by all of that firm's clients. Submitting a batch and finding out what happened to it are two separate events, sometimes hours apart. Runs are long, partially successful, and interruptible. Details below.

**Corrections.** Errors surface after filings are accepted, and by then the form has already gone out. The vendor received their copy in late January and may have filed their own return using it. A correction means a corrected form to the IRS, a corrected copy to the vendor, a penalty assessed per incorrect form, and a conversation the client has to have with a contractor they hired. The original filing stays in the record permanently alongside the correction. These are not routine edits.

**Exception handling.** Much of a firm's January labor is a queue of items that need a person: a vendor with no TIN, a filing the IRS rejected on a name/TIN mismatch, an export that hasn't arrived with four days left, a vendor whose total changed after their 1099 went out, a batch submitted six hours ago that still hasn't come back, a vendor just under threshold a preparer wants to file anyway. Each has a different next action and a different urgency. The person working the queue has the firm's entire book in front of them and forty minutes. Design for them.

**Access and audit.** Firms never see each other's data. Within a firm, not everyone needs a full TIN to do their job, and the firm has to be able to answer — years later, to an auditor or after an incident — who transmitted what, who corrected what, and who looked at whose TIN. Each firm's transmitter credentials live in this system and are used on its behalf; anyone who obtains them can file in that firm's name.

### What makes this harder than an import-and-submit problem

Five things, and they're why we picked this problem.

**Being wrong in the direction of doing too much is expensive.** A duplicate 1099 is an incorrect filing and carries everything described under Corrections. There is no cheap way to undo one.

**You often don't know what happened.** The endpoint acknowledges asynchronously, and it sometimes fails in ways that leave you unable to tell whether your filings landed. A filing's state is not a boolean and the unknowns are not all the same unknown.

**The books change after you file.** A bookkeeper closes the books on January 12, the firm files, and on January 28 the bookkeeper finds an unrecorded invoice and sends a corrected export. Some vendors' totals are now different, a few crossed the threshold who hadn't, and one who did no longer does. This is a re-import that is neither a duplicate nor a fresh client.

**One channel, every client.** The rate budget belongs to the firm rather than the client, and checking on past submissions draws from the same budget as making new ones. Because a single call can only carry one client's filings, the number of calls a firm needs is driven by how many clients it has, not how many forms. On the last night there is a long queue and one pipe. What goes first is your call, and so is what the firm can see while it waits.

**Volume is a cliff, not a slope.** The majority of a season's filings move in the final seventy-two hours, because that's when clients respond. A system that's comfortable at average throughput is not the system that matters.

### The IRS transmission endpoint

This is a third-party system — the IRS's — and there's no sandbox for it, so you'll be standing in for it yourself. The stub is a small piece of work and isn't what we're evaluating; how your system behaves against it is. Keep it configurable and swappable for a real client, since we'll run your system at the rates below and may substitute our own implementation.

What follows is how the endpoint behaves in production, not an API reference. The interface, payload shape, and error types are yours to design.

**Submitting**
**
- A **submission call** carries at most **100 filings**, all of which must belong to the **same client**. It accepts an **idempotency key**.
- A successful call returns a **receipt** identifying the submission. It does **not** tell you whether any filing was accepted.
- **7% of submission calls fail before any filing in them is recorded.** Nothing reached the IRS. Retrying is safe.
- **5% of submission calls record every filing successfully and then return an error anyway.** Those filings are live at the IRS. You never see the receipt. This is real behavior, not a contrivance, and it's the most important line in this document.
- A call takes between **0.1 and 1.6 seconds**, unpredictably.

**Finding out what happened**

- Acknowledgment is retrieved by a separate **status call**, which accepts either a receipt **or an idempotency key**.
- Acknowledgment is usually available **two to twenty minutes** after submission, occasionally several hours later, and very occasionally never — a small number of submissions have to be chased down by a human.
- Once acknowledged, results come back **per filing, not per batch**. Individual filings inside an otherwise successful submission can be rejected — a TIN that isn't nine digits, a TIN beginning `000`, a non-positive amount — while the rest are accepted. Each rejection carries a reason code, and each accepted filing gets a **record identifier**.
- A correction references the record identifier of the filing it replaces, so nothing can be corrected until it has been acknowledged.

**Shared budget**

- Each firm gets **20 calls per rolling 60 seconds** in total, counted across all of its clients and across both submissions and status checks. Calls beyond that are refused until the window clears.

**The IRS's record of what you've submitted survives your process restarting. Yours needs to as well.**

**The bar is that this runs unattended overnight on February 1 and the firm can trust what it's looking at the next morning.**

## Requirements

**Data and ingestion**

- Provide a seed generator that produces a realistic book: at least **20 million ledger lines** across five thousand business clients, spread over **more than one firm**, and containing the kinds of data problems described above. Parameterize it so it can also produce a smaller set.
- A **2,000,000-line export** must import in under **five minutes**.
- Re-importing an identical export must leave the database in an identical state, verifiably.
- Re-importing a revised export must update what changed and nothing else.

**Determination**

- A full determination pass across the entire seeded book must complete in under **two minutes**.
- When a revised export changes a few hundred lines for a client with two million of them, that client's determination and the firm-wide counts must both be current within **one second** of the import finishing. That number is deliberately below what it costs to re-examine that client's ledger.

**Transmission and corrections**

- An interrupted transmission run must produce zero duplicate filings.
- Every filing must either reach a terminal state or be explicitly surfaced as unresolved with a reason. Nothing may sit silently stuck, and "we stopped retrying" is not a terminal state.
- Two people correcting the same filing at the same time must produce exactly one correction.

**Review**

- The firm-wide exception queue — first page and its category counts — must load in under **half a second at p95** against the full seeded book.
- After an overnight run, a staff member must be able to determine within a minute which clients are fully filed, which are partially filed, which are waiting on the IRS, and which need someone's attention.

**Security**

- Firm data must be isolated.
- Vendor TINs are among the most sensitive data in the system — for a sole proprietor, the TIN is their Social Security number — and they're also the key you look vendors up by. They should be masked by default anywhere they're shown or exported, and revealable only through an action that gets recorded.
- A firm's transmitter credentials let anyone holding them file with the IRS in that firm's name, see what it has filed, and correct it. Secure them accordingly.
- Transmissions, corrections, and TIN reveals must land in an audit trail that can't be edited after the fact.

Ship a benchmark harness we can run, and report your numbers in the write-up along with what dominates them. We'll re-run these ourselves, and we understand that a figure from your laptop isn't a production measurement — we're reading order of magnitude, and whether you measured rather than assumed. The one exception is time spent transmitting to the IRS: that's bounded by their endpoint's latency and the rate budget they give you, so there's nothing there to make faster. What's yours to get right is correctness and how you allocate the calls.

On testing: we care much more about tests that would have caught a real outage than about coverage. The kind of thing we mean is a test that kills a transmission run mid-batch and asserts the state you recover to is the right one. Write the ones you'd want on call.

## Details

We're evaluating the parts of the system that are specific to this problem. The supporting functionality that any application needs — authentication, account management, notifications, anything you'd normally hand to a third party — isn't part of that. Stub, omit, or do the minimum, as you prefer. Roles are the exception, since not everyone should see a full TIN: a coarse distinction is enough, and we're not looking for a permissions framework.

There's one real requirement we're leaving out. The firm transmits, but the client is the filer of record, so someone at the client has to approve a set of filings before it goes out — and years later the firm needs to be able to say who approved what and when. Assume that's already happened and filings reach you ready to send. Don't build it; tell us in the write-up how you'd model it.

Ruby on Rails is strongly preferred — it's what we run, and it's what we'll be talking about afterward. Database and job backend are your call. We're not evaluating deployment topology, so keep things simple enough that we can run it locally, and keep every change defensible to a team maintaining it in February.

Where this brief is ambiguous, make a decision and document the assumption. We'd rather see a decisive interpretation you can defend than a hedge that covers everything shallowly. Scoping is a significant part of this job, and a clearly reasoned omission is better than a shaky implementation.

## What To Submit

**The repository.** Assign @seanmcoleman read access to a new GitHub repository containing all code, with whatever instructions we need to start it and to run your benchmark harness. Assume we have a normal dev environment and nothing else. We'll run it locally — there's no need to deploy or host anything.

**A video**, roughly ten minutes, screen-sharing the running application and walking through the code. We're more interested in why than what.

**A write-up** in a file at the repository root, answering these:

- What did you build?
- What are your numbers, and how did you measure them? Include the query plan behind anything you optimized.
- How did you convince yourself this is correct when it gets interrupted?
- What did you deliberately leave out, and why?
- Where does this break? Give us the load, data shape, or timing that takes it down.
- If you had two more weeks on this, what would you do?
- What would you have asked us, if you could have?

## On AI Tools

Use whatever you'd use on the job, including AI, since that's how we work. But anything you ship, you own: we'll ask you to extend it, in detail, in a later conversation.
