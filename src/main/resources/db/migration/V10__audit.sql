-- =====================================================================================
-- V10 -- The audit log.
--
-- TWO LOGS, DELIBERATELY NOT MERGED.
--
--   app.transmission_attempt and the filing states are DOMAIN HISTORY: high-volume,
--   machine-generated, load-bearing for reconciliation. They exist because the system
--   needs them to work correctly.
--
--   app.audit_event is a SECURITY AND COMPLIANCE RECORD: low-volume, actor-centric,
--   append-only, and readable by someone who does not know the filing model. It exists
--   because a person may later have to answer "who did that, and when".
--
-- Merging them produces a table that is bad at both jobs -- too voluminous to audit, too
-- security-shaped to reconcile against. The rule that keeps audit_event small without
-- losing anything an auditor needs:
--
--   MACHINE transitions are audited at the RUN or BATCH level.
--   HUMAN actions are audited PER ACTION, carrying a pointer to what they caused.
--
-- A filing run that transmits 10,000 forms writes a handful of audit events; a person
-- forcing one filing's state writes exactly one. Over a season that keeps this table in
-- the hundreds to low thousands.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- audit_event -- append-only, hash-chained per firm.
--
-- APPEND-ONLY IS STRUCTURAL, not conventional. Three independent mechanisms:
--
--   1. The grants are revoked (at the foot of this file -- see the note there).
--   2. There is deliberately NO policy for UPDATE or DELETE. Under row-level security a
--      command with no permissive policy matches zero rows -- so even a mistakenly
--      re-granted DELETE would affect nothing. This is the one that survives an operator
--      error, because it fails safe rather than loudly.
--   3. The trigger below, which also stops the table OWNER -- the one actor who could
--      re-grant to themselves, and therefore the one the first two cannot cover.
--
-- HONEST FRAMING, and it belongs in the write-up rather than being quietly omitted:
-- a hash chain is tamper-EVIDENT, not tamper-PROOF. Anyone who can write both this table
-- and app.audit_chain_head can recompute the chain and leave no trace. Real resistance
-- requires the head to live somewhere the application cannot reach, so every run prints
-- its chain head to stdout and `verify-audit --pin` writes it to a file outside the
-- database. Production would publish it to WORM storage or a transparency log; that is a
-- deployment concern this project states rather than pretends to have solved.
-- -------------------------------------------------------------------------------------
create table app.audit_event (
  id            bigint      generated always as identity,
  firm_id       bigint      not null default app.current_firm_id(),

  -- Per-firm monotonic sequence. Deliberately NOT the identity column: `id` comes from a
  -- shared sequence, so gaps in it are ordinary (a rolled-back transaction burns a value)
  -- and cannot be distinguished from a deletion. `seq` is assigned from the chain head
  -- inside the same transaction as the insert, so a gap here is evidence.
  seq           bigint      not null,

  occurred_at   timestamptz not null default clock_timestamp(),

  -- Who. 'system:<component>' for machine actions, so an unattributed event is impossible
  -- to write by accident -- the column is NOT NULL and there is no default.
  actor         text        not null,
  actor_role    text,

  action        text        not null,
  entity_type   text,
  entity_id     text,

  -- What changed, and what it was before. jsonb rather than a wide column set because an
  -- audit record has to survive the schema it describes: a column added to app.filing in
  -- 2027 must not make 2025's audit rows unreadable.
  detail        jsonb       not null default '{}'::jsonb,

  -- The chain. hash = sha256(prev_hash || canonical(event)), computed in Java by the same
  -- code that verifies it -- see AuditService.
  prev_hash     bytea       not null,
  hash          bytea       not null,

  constraint audit_event_pk     primary key (firm_id, id),
  constraint audit_event_seq_uk unique (firm_id, seq),
  constraint audit_event_actor_ck check (actor <> ''),
  constraint audit_event_hash_ck  check (length(hash) = 32 and length(prev_hash) = 32)
);

create index audit_event_time_ix   on app.audit_event (firm_id, occurred_at desc);
create index audit_event_actor_ix  on app.audit_event (firm_id, actor, occurred_at desc);
create index audit_event_entity_ix on app.audit_event (firm_id, entity_type, entity_id);


-- -------------------------------------------------------------------------------------
-- audit_chain_head -- one row per firm: the last sequence number and the last hash.
--
-- Separate from audit_event so appending can take a row lock on exactly one row rather
-- than reading MAX(seq) -- which under READ COMMITTED is a check-then-act race that two
-- concurrent writers both win, producing two events claiming the same seq. The unique
-- constraint would catch that, but as a failed transaction at 2 a.m. rather than as a
-- correctly serialized append.
--
-- The lock is held for microseconds because events are buffered and flushed at
-- beforeCommit, so an audited transaction never holds this row across its own work.
-- -------------------------------------------------------------------------------------
create table app.audit_chain_head (
  firm_id    bigint      not null default app.current_firm_id(),
  seq        bigint      not null default 0,
  hash       bytea       not null,
  updated_at timestamptz not null default clock_timestamp(),

  constraint audit_chain_head_pk primary key (firm_id),
  constraint audit_chain_head_hash_ck check (length(hash) = 32)
);


-- -------------------------------------------------------------------------------------
-- The genesis row for every existing firm.
--
-- A chain needs an anchor that is not itself an event, otherwise "the chain is empty" and
-- "the chain was emptied" are the same observation. The genesis hash is a fixed constant,
-- so a verifier can check the first real event without being told anything out of band.
-- -------------------------------------------------------------------------------------
do $$
declare
  f record;
begin
  for f in select id from app.firm order by id loop
    perform set_config('app.current_firm_id', f.id::text, true);
    insert into app.audit_chain_head (firm_id, seq, hash)
    values (f.id, 0, sha256('readiness:audit:genesis:v1'::bytea))
    on conflict (firm_id) do nothing;
  end loop;
end $$;


-- -------------------------------------------------------------------------------------
-- The third mechanism: a trigger that stops even the owner.
--
-- SECURITY DEFINER is not used and is not needed -- the trigger function runs with the
-- privileges of whoever fires it, and all it does is refuse. Making it a definer function
-- would add trust for no gain.
-- -------------------------------------------------------------------------------------
create or replace function app.deny_audit_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception
    'app.audit_event is append-only (attempted % by %)', tg_op, session_user
    using errcode = '42501',
          hint = 'Correct a mistaken audit entry by appending a correcting event, never '
                 'by editing one. The chain is what makes that distinction meaningful.';
end $$;

create trigger audit_event_append_only
  before update or delete on app.audit_event
  for each statement
  execute function app.deny_audit_mutation();


-- -------------------------------------------------------------------------------------
-- The chain head is NOT append-only -- it is a moving pointer and must be updatable.
--
-- It is, however, the single row whose modification would let someone rewrite history
-- undetectably, so DELETE is denied: losing the head would let a truncated chain
-- re-anchor at a new genesis and verify cleanly.
-- -------------------------------------------------------------------------------------
create or replace function app.deny_chain_head_delete()
returns trigger
language plpgsql
as $$
begin
  raise exception 'app.audit_chain_head may not be deleted (attempted by %)', session_user
    using errcode = '42501';
end $$;

create trigger audit_chain_head_no_delete
  before delete on app.audit_chain_head
  for each statement
  execute function app.deny_chain_head_delete();


-- -------------------------------------------------------------------------------------
-- Grants and policies for the audit tables, applied HERE rather than in the repeatable
-- grants migration -- and that placement is the result of getting it wrong first.
--
-- These originally lived in R__grants_and_rls.sql, guarded on the table existing. They
-- never ran. A repeatable migration re-executes only when its OWN checksum changes, so
-- this migration created app.audit_event in one migrate, R__ was unchanged and did not
-- re-run, and the table sat there with the generic firm_isolation policy and full DML
-- grants. Nothing failed; the append-only guarantee was simply absent.
--
-- A repeatable migration is the right place for a SWEEP over whatever exists. It is the
-- wrong place for hardening that one specific table needs, because "runs eventually, if
-- someone edits an unrelated file" is not a guarantee. Table-specific policy belongs with
-- the CREATE TABLE, where it is applied once, at the moment the table exists.
-- -------------------------------------------------------------------------------------
grant select, insert on app.audit_event      to readiness_app;
grant select, insert, update on app.audit_chain_head to readiness_app;

-- Mechanism 1: the grants. Note this also has to undo the blanket grant that
-- `alter default privileges` hands to every new table in this schema.
revoke update, delete, truncate on app.audit_event      from readiness_app;
revoke delete, truncate         on app.audit_chain_head from readiness_app;

-- Mechanism 2: NO policy for UPDATE or DELETE, at all.
--
-- This is the one that survives operator error. Under row-level security a command with
-- no permissive policy matches zero rows -- so if someone later re-grants DELETE by
-- reflex, or a `grant all` sweeps through, the DELETE still affects nothing. It fails
-- safe rather than loudly, which is the right failure for a control nobody is watching.
drop policy if exists firm_isolation on app.audit_event;

create policy audit_insert on app.audit_event for insert to readiness_app
  with check (firm_id = app.current_firm_id());

create policy audit_select on app.audit_event for select to readiness_app
  using (firm_id = app.current_firm_id());

-- The chain head is a moving pointer, so it legitimately needs UPDATE. It keeps the
-- ordinary firm_isolation policy from the sweep; only DELETE is denied, by grant and by
-- the trigger above -- losing the head would let a truncated chain re-anchor at a new
-- genesis and verify cleanly.
