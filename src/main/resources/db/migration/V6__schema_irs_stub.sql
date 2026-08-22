-- =====================================================================================
-- V6 -- The IRS stub's own books.
--
-- This schema models an EXTERNAL system. It is deliberately:
--
--   * NOT firm-scoped and NOT under RLS. Our tenancy rules are ours, not the IRS's. More
--     importantly: the stub is the independent oracle the tests judge against, and if our
--     isolation policies applied to its tables, an assertion about what the "IRS" actually
--     recorded would be filtered by the very mechanism it exists to audit.
--
--   * Reached through a separate DataSource, with an ArchUnit rule forbidding any
--     reference to this schema outside the stub package. The seam is real rather than
--     merely intended.
--
--   * Persisted in PostgreSQL rather than held in memory. This is the non-obvious one:
--     the required kill-and-resume test needs the "IRS" to OUTLIVE the killed worker. An
--     in-memory stub forgets everything on SIGKILL, so every restart would look like a
--     clean slate and failure mode B would be untestable -- the test would pass while
--     proving nothing.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- submissions -- server-side idempotency.
--
-- The key is the primary key, which is what makes a retry a replay rather than a second
-- submission. outcome_returned records what the caller was TOLD, which may differ from
-- what was recorded -- that difference is failure mode B.
-- -------------------------------------------------------------------------------------
create table irs_stub.submission (
  idempotency_key    text        primary key,
  firm_id            bigint      not null,
  client_id          bigint      not null,
  tax_year           smallint    not null,
  receipt_id         text        not null,
  recorded_at        timestamptz not null default clock_timestamp(),

  -- When acknowledgments become visible. Configurable, and zero in tests.
  acks_available_at  timestamptz not null,
  -- "occasionally never" from the brief, made explicit and testable.
  never_acks         boolean     not null default false,

  -- 'RECEIPT'      the caller got the receipt.
  -- 'MODE_B_ERROR' everything was recorded and the caller got an error anyway.
  outcome_returned   text        not null,

  constraint submission_outcome_ck check (outcome_returned in ('RECEIPT', 'MODE_B_ERROR'))
);


-- -------------------------------------------------------------------------------------
-- recorded_filing -- what the IRS believes it holds.
--
-- THERE IS DELIBERATELY NO UNIQUENESS CONSTRAINT ON client_reference.
--
-- That absence is the point. If the stub REFUSED duplicates, a duplicate-producing bug in
-- our transmitter would surface as a stub exception -- and the test would pass for the
-- wrong reason, having proved only that the stub enforces something.
--
-- Instead the stub faithfully records everything it is told, including duplicates, and the
-- test asserts
--     select client_reference, count(*) ... having count(*) > 1
-- against the IRS's own books rather than against our system's beliefs. The stub records;
-- the test judges.
-- -------------------------------------------------------------------------------------
create table irs_stub.recorded_filing (
  id                 bigint generated always as identity primary key,
  idempotency_key    text        not null references irs_stub.submission (idempotency_key),
  client_reference   text        not null,   -- our filing id, echoed back on every ack
  filing_generation  int         not null,
  recipient_tin      text,
  recipient_name     text,
  amount_cents       bigint,
  withholding_cents  bigint,

  ack                text,
  reason_code        text,
  reason_text        text,
  irs_record_id      text,
  recorded_at        timestamptz not null default clock_timestamp(),

  constraint recorded_filing_ack_ck check (ack is null or ack in ('ACCEPTED', 'REJECTED'))
);

create index recorded_filing_key_ix on irs_stub.recorded_filing (idempotency_key);
create index recorded_filing_ref_ix on irs_stub.recorded_filing (client_reference);


-- -------------------------------------------------------------------------------------
-- call_log -- the stub's independent record of every call it received.
--
-- The rate-budget assertion is made against THIS table, not against app.irs_call_log.
-- Checking our own accounting would only prove our accounting is self-consistent; checking
-- the endpoint's proves we never actually exceeded the budget.
-- -------------------------------------------------------------------------------------
create table irs_stub.call_log (
  id                 bigint generated always as identity primary key,
  firm_id            bigint      not null,
  call_type          text        not null,
  idempotency_key    text,
  at                 timestamptz not null default clock_timestamp(),
  outcome            text
);

create index stub_call_log_window_ix on irs_stub.call_log (firm_id, at desc);
