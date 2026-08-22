-- =====================================================================================
-- V4 -- Determination: which vendors require a 1099-NEC, and why.
--
-- THE SHAPE OF THIS SCHEMA IS AN ANSWER TO ONE REQUIREMENT:
--
--   "make it explainable: for any vendor, the system can show which payments counted,
--    which didn't and why, and the total"
--
-- Explainability is therefore a WRITE, not a read. Every "why" is a stored column produced
-- by the same pass that produced the number, so the explanation and the total cannot drift
-- apart -- they are literally the same row of the same query. Nothing is recomputed to
-- answer "why", which also means the answer cannot change under a later rule edit.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- determination_run -- one row per pass, immutable once finished.
--
-- Records the ruleset hash and the name-normalizer version, because "what did we believe
-- about this vendor on February 1 at 23:00" is only answerable if you also know which
-- rules were in force at the time.
-- -------------------------------------------------------------------------------------
create table app.determination_run (
  id             bigint generated always as identity,
  firm_id        bigint      not null default app.current_firm_id(),
  tax_year       smallint    not null,
  mode           text        not null,
  ruleset_hash   text        not null,
  name_norm_version smallint not null,
  threshold_cents bigint     not null,

  started_at     timestamptz not null default clock_timestamp(),
  finished_at    timestamptz,
  state          text        not null default 'RUNNING',

  clients_scanned    int    not null default 0,
  payments_scanned   bigint not null default 0,
  vendors_resolved   bigint not null default 0,
  forms_required     bigint not null default 0,
  exceptions_raised  bigint not null default 0,

  resolve_ms     bigint,
  classify_ms    bigint,
  aggregate_ms   bigint,
  write_payment_ms bigint,
  write_vendor_ms  bigint,
  exception_ms   bigint,
  total_ms       bigint,

  constraint determination_run_pk primary key (firm_id, id),
  constraint determination_run_mode_ck  check (mode in ('FULL', 'INCREMENTAL')),
  constraint determination_run_state_ck check (state in ('RUNNING', 'COMPLETED', 'FAILED'))
);


-- -------------------------------------------------------------------------------------
-- vendor_determination -- the decision, as a slowly-changing dimension.
--
-- WHY SCD-2 RATHER THAN OVERWRITE-IN-PLACE:
--
-- When a penalty notice arrives in June, the question is not "what do we believe now" but
-- "what did we believe when we filed, and under which rules". Overwriting would destroy
-- exactly the evidence that question needs.
--
-- Only DIRTY units are rewritten, so storage grows with CHANGE rather than with
-- runs x vendors. Re-running determination over an unchanged book adds no rows at all.
--
-- vendor_key is 'TIN:<hex>' or 'NAME:<hex>' -- the blind index in hex, never a plaintext
-- TIN. It is scoped to (firm, client) because a 1099-NEC is issued BY the client: the same
-- contractor paid by two different clients is two vendors with two forms, not one.
-- -------------------------------------------------------------------------------------
create table app.vendor_determination (
  id             bigint generated always as identity,
  firm_id        bigint      not null default app.current_firm_id(),
  client_id      bigint      not null,
  vendor_key     text        not null,
  tax_year       smallint    not null,

  valid_from     timestamptz not null default clock_timestamp(),
  valid_to       timestamptz not null default 'infinity',
  run_id         bigint      not null,

  vendor_id      bigint,
  display_name   text        not null,
  tin_bidx       bytea,
  tin_last4      char(4),
  tin_status     text        not null,

  -- How this vendor's identity was established. Stored so a merge is explainable rather
  -- than magic: 'NAME_TIN_PROMOTION' in particular is a decision a human may want to
  -- question, and it should not require re-deriving the whole pass to see that it happened.
  identity_source text       not null,

  -- The full decomposition, not just the answer. A reviewer asking "why is Box 1 $650 when
  -- they were paid $2,400?" gets the subtraction, not a number to take on trust.
  gross_cents        bigint  not null default 0,
  card_excluded_cents bigint not null default 0,
  reversal_cents     bigint  not null default 0,
  non_services_cents bigint  not null default 0,
  out_of_year_cents  bigint  not null default 0,
  reportable_cents   bigint  not null default 0,   -- Box 1, AND the threshold basis
  withholding_cents  bigint  not null default 0,   -- Box 4

  counted_payment_count int  not null default 0,
  total_payment_count   int  not null default 0,

  form_required      boolean not null,
  requirement_reason text    not null,

  -- Set when a missing or malformed TIN means this cannot transmit cleanly. The obligation
  -- still stands; only the transmission is blocked.
  transmit_blocked   boolean not null default false,

  -- Once a filing has gone to the IRS, a later run must not silently rewrite the basis it
  -- was built on. It writes a new version and raises DETERMINATION_CHANGED_AFTER_FILING
  -- instead. This is the seam where determination hands off to corrections.
  locked_by_filing_id uuid,

  constraint vendor_determination_pk primary key (firm_id, id),
  constraint vendor_determination_reason_ck check (
    requirement_reason in ('THRESHOLD_MET', 'BACKUP_WITHHOLDING', 'BELOW_THRESHOLD')),
  constraint vendor_determination_identity_ck check (
    identity_source in ('DIRECT_TIN', 'NAME_TIN_PROMOTION', 'NAME_ONLY',
                        'AMBIGUOUS_NAME_MULTI_TIN', 'MANUAL_ALIAS'))
);

-- At most one current version per vendor-year. A partial unique index rather than a plain
-- one, because history rows deliberately repeat the key.
create unique index vendor_determination_current_uk
  on app.vendor_determination (firm_id, client_id, vendor_key, tax_year)
  where valid_to = 'infinity';

create index vendor_determination_required_ix
  on app.vendor_determination (firm_id, tax_year, form_required)
  where valid_to = 'infinity';

create index vendor_determination_client_ix
  on app.vendor_determination (firm_id, client_id, tax_year)
  where valid_to = 'infinity';


-- -------------------------------------------------------------------------------------
-- payment_determination -- per-payment evidence.
--
-- A DELIBERATE ASYMMETRY WITH vendor_determination: the decision is versioned, the
-- evidence is materialised. This table is overwritten in place rather than kept as
-- history.
--
-- Reasoning: 1M rows x N runs of history is unjustifiable storage for something that is
-- always reconstructible -- the immutable vendor-level snapshot plus the ledger rows are
-- enough to re-derive any past per-payment verdict. Version the decision; materialise the
-- evidence.
-- -------------------------------------------------------------------------------------
create table app.payment_determination (
  firm_id        bigint      not null default app.current_firm_id(),
  ledger_line_id bigint      not null,
  client_id      bigint      not null,
  tax_year       smallint    not null,
  run_id         bigint      not null,

  vendor_key     text        not null,
  identity_source text       not null,

  -- Why this payment did or did not count. Joins to app.reason_code for the human text, so
  -- the CLI, the page, and the tests all render the same words.
  disposition    text        not null,
  counted_cents  bigint      not null default 0,

  constraint payment_determination_pk primary key (firm_id, ledger_line_id)
);

create index payment_determination_vendor_ix
  on app.payment_determination (firm_id, client_id, vendor_key, tax_year);


-- -------------------------------------------------------------------------------------
-- determination_exception -- what needs a person.
--
-- Generated by the same pass that produces the numbers, so the exception list cannot
-- disagree with the determination it came from.
--
-- SEVERITY:
--   BLOCKING -- a form is owed and cannot be transmitted as things stand (missing TIN,
--               malformed TIN). Someone has to act before this can be filed.
--   REVIEW   -- the filing can proceed, but a human should look (ambiguous identity,
--               unknown payment method, negative reportable).
-- -------------------------------------------------------------------------------------
create table app.determination_exception (
  id             bigint generated always as identity,
  firm_id        bigint      not null default app.current_firm_id(),
  run_id         bigint      not null,
  client_id      bigint      not null,
  vendor_key     text        not null,
  tax_year       smallint    not null,

  code           text        not null,
  severity       text        not null,
  detail         jsonb       not null default '{}'::jsonb,

  raised_at      timestamptz not null default clock_timestamp(),
  resolved_at    timestamptz,
  resolved_by    text,
  resolution_note text,

  constraint determination_exception_pk primary key (firm_id, id),
  constraint determination_exception_severity_ck check (severity in ('BLOCKING', 'REVIEW'))
);

-- One open exception per (vendor, code). Makes exception generation idempotent: re-running
-- determination cannot pile up duplicates of the same unresolved problem.
create unique index determination_exception_open_uk
  on app.determination_exception (firm_id, client_id, vendor_key, tax_year, code)
  where resolved_at is null;

create index determination_exception_run_ix
  on app.determination_exception (firm_id, run_id, severity);


-- -------------------------------------------------------------------------------------
-- vendor_alias -- a human's decision about identity, honoured deterministically.
--
-- The escape hatch for the cases the resolver deliberately refuses to guess at. No fuzzy
-- matching is ever applied automatically: a false merge files one contractor's income
-- under another's identity, which is a wrong tax form and a disclosure incident, and it is
-- nearly invisible because the totals look plausible.
--
-- So similarity is offered as a SUGGESTION on the exceptions page, and when a person
-- accepts it, the decision is recorded here and applied from then on. Machine proposes,
-- human disposes, database remembers.
-- -------------------------------------------------------------------------------------
create table app.vendor_alias (
  id                 bigint generated always as identity,
  firm_id            bigint not null default app.current_firm_id(),
  client_id          bigint not null,
  alias_vendor_key   text   not null,
  canonical_vendor_key text not null,
  created_by         text   not null,
  created_at         timestamptz not null default clock_timestamp(),
  note               text,

  constraint vendor_alias_pk primary key (firm_id, id),
  constraint vendor_alias_uk unique (firm_id, client_id, alias_vendor_key),
  -- An alias pointing at itself would make the resolver loop.
  constraint vendor_alias_not_self_ck check (alias_vendor_key <> canonical_vendor_key)
);
