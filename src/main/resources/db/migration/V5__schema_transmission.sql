-- =====================================================================================
-- V5 -- Transmission: filings, batches, attempts, attention items, and the rate budget.
--
-- THE ORGANIZING CONSTRAINT: 20 API calls per rolling 60 seconds per firm, shared between
-- submissions and status checks. A submission carries at most 100 filings; a status call
-- resolves at most 100. So the whole system's capacity is ~2,000 filings/minute per firm,
-- and PostgreSQL is nowhere near the bottleneck.
--
-- Everything here is therefore about spending twenty calls a minute well, and never
-- spending one twice on the same filing.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- filing -- one per (client, tax year, vendor). The business obligation.
--
-- id is a UUIDv5 derived from (firm, client, tax_year, vendor_key), NOT a random UUID and
-- NOT a sequence. This is the single most important choice in the anti-duplicate design,
-- and the reason is not obvious:
--
--   The most common way to build a "correct" idempotent transmitter and still ship
--   duplicates is that determination gets re-run -- a revised export arrives, or an
--   operator re-runs a task -- it mints fresh random ids, those flow into a fresh
--   idempotency key, and a filing that is ALREADY LIVE at the IRS is transmitted again
--   under a key the server has never seen. Every layer behaves correctly and the vendor
--   receives two forms.
--
--   Transmission idempotency is only ever as strong as determination idempotency.
--
-- Deriving the id from business identity makes re-determination converge on the same row
-- instead of creating a new one.
-- -------------------------------------------------------------------------------------
create table app.filing (
  id                 uuid        not null,
  firm_id            bigint      not null default app.current_firm_id(),
  client_id          bigint      not null,
  tax_year           smallint    not null,
  vendor_key         text        not null,

  -- The attempt epoch. Increments ONLY on positive proof the previous epoch is dead:
  -- a status call returned Unknown, or a human remediated a rejection. Never while a
  -- filing might still be live.
  generation         int         not null default 1,

  state              text        not null,

  -- Covers every field that goes on the wire. Recomputed and compared before dispatch, so
  -- a filing whose content changed after sealing cannot be shipped under a key derived
  -- from the old content.
  content_hash       bytea       not null,

  amount_cents       bigint      not null,
  withholding_cents  bigint      not null default 0,

  recipient_name     text        not null,
  recipient_tin_ct   bytea,
  recipient_tin_bidx bytea,
  tin_last4          char(4),
  tin_status         text        not null,

  irs_record_id      text,
  reject_code        text,
  reject_detail      text,

  determination_run_id bigint,
  state_changed_at   timestamptz not null default clock_timestamp(),
  created_at         timestamptz not null default clock_timestamp(),
  lock_version       int         not null default 0,

  constraint filing_pk primary key (firm_id, id),
  constraint filing_natural_uk unique (firm_id, client_id, tax_year, vendor_key, generation),
  constraint filing_client_fk foreign key (firm_id, client_id) references app.client (firm_id, id),
  constraint filing_state_ck check (state in (
      'DRAFT', 'READY_TO_TRANSMIT', 'BLOCKED', 'BATCHED',
      'SUBMITTED_UNACKNOWLEDGED', 'ACCEPTED', 'REJECTED')),
  -- An accepted filing without a record identifier would be a claim we cannot evidence.
  constraint filing_accepted_ck check (state <> 'ACCEPTED' or irs_record_id is not null),
  constraint filing_rejected_ck check (state <> 'REJECTED' or reject_code is not null)
);

-- The planner's access path: what is eligible to batch, for one client.
create index filing_ready_ix on app.filing (firm_id, client_id, id)
  where state = 'READY_TO_TRANSMIT';

create index filing_state_ix on app.filing (firm_id, tax_year, state);
create index filing_client_ix on app.filing (firm_id, client_id, tax_year);


-- -------------------------------------------------------------------------------------
-- filing_batch -- the transmission envelope, and also the work queue.
--
-- There is deliberately no separate outbox or job table for transmission. This table IS
-- the queue: next_action_at schedules it, lease_owner claims it. That means "the job and
-- the business state commit together" is trivially true rather than something to argue --
-- there is only one row to commit.
--
-- STATE MEANINGS, and the distinction the whole design rests on:
--   SEALED       membership and key committed; NOTHING has left this process. Safe to abandon.
--   DISPATCHED   at least one request has left for this key. IRS state is UNKNOWN.
--   SUBMITTED    a receipt is in hand. IRS state is KNOWN-RECORDED.
--   ACKNOWLEDGED every member resolved. Terminal.
--   VOID         reconciliation PROVED the IRS has no record. Members released. Terminal.
--
-- SEALED -> DISPATCHED is committed BEFORE a single byte goes on the wire. That is the
-- write-ahead barrier: once DISPATCHED, only evidence from the IRS can move this batch --
-- never an error, never a timeout, never a crash.
-- -------------------------------------------------------------------------------------
create table app.filing_batch (
  id                 uuid        not null,
  firm_id            bigint      not null default app.current_firm_id(),
  client_id          bigint      not null,
  tax_year           smallint    not null,

  idempotency_key    text        not null,
  state              text        not null,
  filing_count       int         not null,

  receipt_id         text,
  attempt_count      int         not null default 0,
  poll_count         int         not null default 0,

  -- Set for every DISPATCHED batch at startup. The planner is gated on this being clear
  -- for the firm, so no new submission can go out while any batch's fate is unknown.
  needs_reconcile    boolean     not null default false,

  sealed_at          timestamptz not null default clock_timestamp(),
  first_dispatch_at  timestamptz,
  submitted_at       timestamptz,
  acknowledged_at    timestamptz,

  -- The scheduling column. A batch is due when next_action_at <= now.
  next_action_at     timestamptz not null default clock_timestamp(),
  lease_owner        text,
  lease_expires_at   timestamptz,

  last_error_class   text,
  last_error_detail  text,

  constraint filing_batch_pk primary key (firm_id, id),
  -- Two planners that independently select the same filings compute the SAME key, so a
  -- double-plan collides here and is caught rather than producing two live submissions.
  constraint filing_batch_idem_uk unique (firm_id, idempotency_key),
  constraint filing_batch_client_fk foreign key (firm_id, client_id) references app.client (firm_id, id),
  constraint filing_batch_state_ck check (state in
      ('SEALED', 'DISPATCHED', 'SUBMITTED', 'ACKNOWLEDGED', 'VOID')),
  -- The stub accepts at most 100 filings, all for one client. Constraints, not comments.
  constraint filing_batch_size_ck check (filing_count between 1 and 100),
  constraint filing_batch_receipt_ck check (state <> 'SUBMITTED' or receipt_id is not null),
  constraint filing_batch_dispatch_ck check (state = 'SEALED' or state = 'VOID'
                                             or first_dispatch_at is not null)
);

-- The worker's claim query: what is due for this firm.
create index filing_batch_due_ix on app.filing_batch (firm_id, next_action_at)
  where state in ('SEALED', 'DISPATCHED', 'SUBMITTED');

create index filing_batch_reconcile_ix on app.filing_batch (firm_id)
  where needs_reconcile;


-- -------------------------------------------------------------------------------------
-- filing_batch_member -- where per-batch receipts meet per-filing acknowledgments.
--
-- THE SINGLE MOST IMPORTANT LINE OF DDL IN THIS PROJECT is the unique constraint below.
--
-- It says, with no predicate and no trigger: a filing may be submitted AT MOST ONCE per
-- attempt epoch, ever. Not "at most one live batch" -- at most one, full stop. To submit a
-- filing again you must first bump its generation, and generation only bumps on proof of
-- non-delivery or on human remediation.
--
-- Note the absence of a WHERE clause. A partial unique index on some "is_live" flag would
-- require maintaining that flag correctly, which is code, which is where bugs live. This
-- version is enforced by the shape of the data: every duplicate-producing bug I can
-- construct has to violate this constraint to reach the wire, and it cannot -- the insert
-- fails and the planning transaction rolls back.
-- -------------------------------------------------------------------------------------
create table app.filing_batch_member (
  firm_id            bigint      not null default app.current_firm_id(),
  batch_id           uuid        not null,
  filing_id          uuid        not null,
  filing_generation  int         not null,

  -- Frozen at seal time. The idempotency key is derived from these, so re-deriving the key
  -- later verifies that nothing mutated underneath an in-flight batch.
  content_hash       bytea       not null,

  ack                text,
  ack_code           text,
  ack_detail         text,
  irs_record_id      text,
  acked_at           timestamptz,

  constraint filing_batch_member_pk primary key (firm_id, batch_id, filing_id),
  constraint filing_batch_member_batch_fk
    foreign key (firm_id, batch_id) references app.filing_batch (firm_id, id) on delete restrict,
  constraint filing_batch_member_filing_fk
    foreign key (firm_id, filing_id) references app.filing (firm_id, id),
  constraint filing_batch_member_ack_ck check (ack is null or ack in ('ACCEPTED', 'REJECTED')),

  -- *** THE anti-duplicate invariant ***
  constraint uq_one_submission_per_epoch unique (firm_id, filing_id, filing_generation)
);

create index filing_batch_member_filing_ix on app.filing_batch_member (firm_id, filing_id);
create index filing_batch_member_unacked_ix on app.filing_batch_member (firm_id, batch_id)
  where ack is null;


-- -------------------------------------------------------------------------------------
-- transmission_attempt -- the write-ahead intent log, and the compliance record.
--
-- A row is written BEFORE each outbound call, so even a process that dies mid-request
-- leaves evidence that a call was attempted. outcome IS NULL means "we never learned what
-- happened" -- which is a real and important state, not missing data.
-- -------------------------------------------------------------------------------------
create table app.transmission_attempt (
  id                 bigint generated always as identity,
  firm_id            bigint      not null default app.current_firm_id(),
  batch_id           uuid        not null,
  attempt_no         int         not null,
  call_type          text        not null,
  idempotency_key    text        not null,
  worker_id          text        not null,

  started_at         timestamptz not null default clock_timestamp(),
  finished_at        timestamptz,
  outcome            text,
  error_class        text,
  error_detail       text,
  receipt_id         text,

  constraint transmission_attempt_pk primary key (firm_id, id),
  constraint transmission_attempt_uk unique (firm_id, batch_id, call_type, attempt_no),
  constraint transmission_attempt_type_ck check (call_type in ('SUBMIT', 'STATUS'))
);

create index transmission_attempt_batch_ix on app.transmission_attempt (firm_id, batch_id, id desc);


-- -------------------------------------------------------------------------------------
-- attention_item -- why a person is needed.
--
-- The brief requires both that every filing be in exactly one state AND that "we stopped
-- retrying" be something a human gets shown. Those pull against each other if attention
-- conditions are modelled as states: you lose the information about where the filing
-- actually is in its lifecycle.
--
-- Resolution: STATE is position in the lifecycle; ATTENTION ITEM is why someone is needed.
-- Orthogonal, both first-class. An attention item never changes a filing's state and never
-- changes polling cadence -- polling continues exactly as before.
-- -------------------------------------------------------------------------------------
create table app.attention_item (
  id                 bigint generated always as identity,
  firm_id            bigint      not null default app.current_firm_id(),
  client_id          bigint,
  entity_type        text        not null,
  entity_id          text        not null,
  type               text        not null,
  severity           smallint    not null,
  detail             jsonb       not null default '{}'::jsonb,

  first_seen_at      timestamptz not null default clock_timestamp(),
  resolved_at        timestamptz,
  resolved_by        text,

  constraint attention_item_pk primary key (firm_id, id),
  constraint attention_item_entity_ck check (entity_type in ('FILING', 'BATCH', 'VENDOR')),
  constraint attention_item_type_ck check (type in (
      'VENDOR_MISSING_TIN', 'PREFLIGHT_VALIDATION_FAILED', 'FILING_REJECTED',
      'SUBMISSION_UNACKNOWLEDGED_TOO_LONG', 'SUBMISSION_INDETERMINATE_TOO_LONG',
      'TRANSMISSION_RETRIES_EXHAUSTED', 'ACK_RECONCILIATION_MISMATCH',
      'AMENDED_DATA_FOR_INFLIGHT_FILING', 'RATE_BUDGET_BREACH_DETECTED',
      'ORPHANED_BATCH_MEMBERSHIP', 'DETERMINATION_CHANGED_AFTER_FILING'))
);

-- One open item per (entity, type). Makes raising an item idempotent: a sweeper running
-- every 30 seconds cannot pile up thousands of copies of the same unresolved problem.
create unique index attention_item_open_uk
  on app.attention_item (firm_id, entity_type, entity_id, type)
  where resolved_at is null;

create index attention_item_open_ix on app.attention_item (firm_id, severity, first_seen_at)
  where resolved_at is null;


-- -------------------------------------------------------------------------------------
-- irs_call_log -- the rate budget, as a sliding-window log.
--
-- WHY A LOG AND NOT A TOKEN BUCKET. A bucket with capacity 20 refilling at 20/60s permits
-- 20 calls at t=0 and one more at t=3s -- 21 in the rolling window. A token bucket whose
-- burst equals its capacity implements an AVERAGE, not a rolling window. Making it safe
-- requires burst=1 (one call every 3 seconds), which is correct but drains a backlog far
-- more slowly than the budget actually allows, and throughput is the scarce resource on
-- February 1.
--
-- A log implements the stated semantics exactly: "20 calls per rolling 60 seconds" IS
-- count(*) over the last 60 seconds < 20. There is no approximation to defend.
--
-- It is also restart-safe structurally rather than by effort: there is no in-memory state
-- to lose. A process that dies and comes back sees the same rows.
--
-- And it doubles as the compliance audit trail the security section asks for: every
-- outbound call, by whom, for which firm, with what outcome.
-- -------------------------------------------------------------------------------------
create table app.irs_call_log (
  id                 bigint generated always as identity,
  firm_id            bigint      not null default app.current_firm_id(),
  -- clock_timestamp(), NOT now(): now() is transaction-START time and stays frozen for the
  -- transaction's duration, which would backdate calls made by a long transaction and
  -- silently widen the window.
  called_at          timestamptz not null default clock_timestamp(),
  call_type          text        not null,
  batch_id           uuid,
  worker_id          text        not null,
  outcome            text,

  constraint irs_call_log_pk primary key (firm_id, id),
  constraint irs_call_log_type_ck check (call_type in ('SUBMIT', 'STATUS'))
);

create index irs_call_log_window_ix on app.irs_call_log (firm_id, called_at desc);
