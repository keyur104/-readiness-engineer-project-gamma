-- =====================================================================================
-- V7 -- The freeze rule: sealing a batch freezes a filing's content.
--
-- THE SCENARIO THIS EXISTS FOR:
--
--   A revised export arrives at 2 a.m., in the middle of an overnight filing run. The
--   bookkeeper found a missed invoice, so a vendor's reportable amount changes from $700
--   to $850. Re-determination happily updates the filing row.
--
--   But that filing is already inside a SEALED or DISPATCHED batch, and the batch's
--   idempotency key was derived from the OLD content hash. We would then transmit $700
--   under a key we recorded against $850 -- or, worse, the recomputed key would no longer
--   match and the batch could be re-sent as something the server treats as new.
--
--   The result is a filing whose recorded amount and transmitted amount differ, with no
--   error anywhere and nothing to notice until the vendor's copy disagrees with the IRS's.
--
-- FilingPlanner already refuses to write frozen filings. This trigger is the second line,
-- for the same reason RLS exists: application discipline is a convention, and a convention
-- can be bypassed by a backfill migration, an operator running UPDATE in psql at 2 a.m., or
-- a future code path nobody thought about. A trigger cannot be forgotten.
-- =====================================================================================

create or replace function app.enforce_filing_freeze() returns trigger
language plpgsql
as $$
begin
  -- Only content matters. State transitions, acknowledgment fields, and lock_version must
  -- stay freely writable -- the dispatcher and poller update those constantly, and they are
  -- precisely the columns that SHOULD change while a filing is in flight.
  if old.state in ('BATCHED', 'SUBMITTED_UNACKNOWLEDGED') then
    if new.content_hash is distinct from old.content_hash
       or new.amount_cents is distinct from old.amount_cents
       or new.withholding_cents is distinct from old.withholding_cents
       or new.recipient_name is distinct from old.recipient_name
       or new.recipient_tin_bidx is distinct from old.recipient_tin_bidx
       or new.generation is distinct from old.generation then

      raise exception
        'filing % is in flight (state=%); its content is frozen until the batch settles',
        old.id, old.state
        using errcode = '55006',   -- object_in_use
              hint = 'record a pending amendment and raise AMENDED_DATA_FOR_INFLIGHT_FILING '
                     'instead of mutating a filing whose idempotency key is already committed';
    end if;
  end if;

  -- ACCEPTED is terminal. Nothing about a filing the IRS has accepted may change, because
  -- the only correct way to alter an accepted filing is a corrected return -- a new
  -- artifact alongside the original, never an edit to it. (Corrections are out of scope
  -- here by the brief, and designed in the write-up.)
  if old.state = 'ACCEPTED' and new.state is distinct from old.state then
    raise exception 'filing % is ACCEPTED; that state is terminal', old.id
      using errcode = '55006',
            hint = 'an accepted filing is amended by filing a correction, never by editing it';
  end if;

  return new;
end
$$;

comment on function app.enforce_filing_freeze() is
  'Blocks content changes to filings inside a sealed or dispatched batch, and any transition '
  'out of ACCEPTED. Second line of defence behind FilingPlanner; exists because a convention '
  'can be bypassed and a trigger cannot.';

create trigger filing_freeze_guard
  before update on app.filing
  for each row execute function app.enforce_filing_freeze();


-- -------------------------------------------------------------------------------------
-- pending_amendment -- what a frozen filing's new data becomes instead.
--
-- Without somewhere to put it, the freeze rule would silently DISCARD a real change: the
-- bookkeeper's correction would be rejected by the trigger and simply lost, which is a
-- worse outcome than the bug the freeze prevents.
--
-- So the amendment is recorded, an attention item is raised, and a human decides once the
-- original settles. That decision is exactly the corrections workflow the brief puts out of
-- scope -- so this table is where Part 1 and Part 3 hand off to it, and the seam is real
-- rather than hypothetical.
-- -------------------------------------------------------------------------------------
create table app.pending_amendment (
  id                 bigint generated always as identity,
  firm_id            bigint      not null default app.current_firm_id(),
  filing_id          uuid        not null,
  client_id          bigint      not null,

  observed_at        timestamptz not null default clock_timestamp(),
  determination_run_id bigint,

  old_amount_cents      bigint   not null,
  new_amount_cents      bigint   not null,
  old_withholding_cents bigint   not null,
  new_withholding_cents bigint   not null,
  new_recipient_name    text,

  resolved_at        timestamptz,
  resolved_by        text,
  resolution         text,

  constraint pending_amendment_pk primary key (firm_id, id),
  constraint pending_amendment_filing_fk
    foreign key (firm_id, filing_id) references app.filing (firm_id, id),
  constraint pending_amendment_resolution_ck check (
    resolution is null or resolution in ('SUPERSEDED', 'CORRECTION_REQUIRED', 'NO_ACTION'))
);

-- One open amendment per filing: re-running determination repeatedly against an in-flight
-- filing must not pile up identical rows.
create unique index pending_amendment_open_uk
  on app.pending_amendment (firm_id, filing_id)
  where resolved_at is null;
