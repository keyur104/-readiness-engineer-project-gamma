-- =====================================================================================
-- V2 -- The ledger: vendors, payment lines, import bookkeeping, and dirty marking.
--
-- Every table here carries firm_id and is auto-protected by the app_force_rls event
-- trigger installed in db/setup.sql, then re-asserted by R__grants_and_rls.sql.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- vendor
--
-- Where plaintext TINs live -- and the ONLY place they live. Tens of thousands of rows,
-- not the million-row ledger. Keeping the most sensitive value in the system out of the
-- big table is a more effective control than any cryptography applied to it, because it
-- keeps TINs out of every backup, replica, and pg_dump of that table.
--
-- natural_key is the blind index that identifies the vendor within a client:
--   HMAC(key, firm_id || ':'   || tin_digits)      when a valid TIN is present
--   HMAC(key, firm_id || ':name:' || normalized)   otherwise
-- The distinct ':name:' domain prefix means a name-derived key can never collide with a
-- TIN-derived one -- without it, a normalized name that happened to be nine digits could
-- resolve to the same vendor as an actual TIN.
--
-- Identity is scoped to (firm, client), never firm-wide. The 1099-NEC is issued BY the
-- client, so aggregating one TIN across two clients would produce a single form covering
-- money that two different payers paid. That scoping is also what makes the dirty-marking
-- proof work at client grain (see determination_dirty_client below).
-- -------------------------------------------------------------------------------------
create table app.vendor (
  id             bigint generated always as identity primary key,
  firm_id        bigint      not null default app.current_firm_id(),
  client_id      bigint      not null,

  natural_key    bytea       not null,
  keyed_by       text        not null,

  display_name   text        not null,
  name_norm      text        not null,
  name_norm_version smallint not null,

  -- Randomized AES-256-GCM. A fresh nonce per write means the ciphertext column leaks no
  -- equality at all; equality lives only in tin_bidx, deliberately and in one place.
  tin_ct         bytea,
  tin_key_ver    smallint,
  tin_bidx       bytea,                    -- = natural_key when keyed_by = 'TIN'
  tin_last4      char(4),
  tin_status     text        not null,
  tin_raw_masked text,                     -- malformed TINs, masked, preserved for a human

  sole_proprietor boolean    not null default false,
  first_seen_run_id bigint,
  last_changed_run_id bigint,

  constraint vendor_firm_id_uk   unique (firm_id, id),
  constraint vendor_natural_uk   unique (firm_id, client_id, natural_key),
  constraint vendor_client_fk    foreign key (firm_id, client_id) references app.client (firm_id, id),
  constraint vendor_keyed_by_ck  check (keyed_by in ('TIN', 'NAME')),
  constraint vendor_tin_status_ck check (tin_status in ('PRESENT', 'MISSING', 'MALFORMED')),
  -- A vendor keyed by TIN must actually have one. Cheap, and it makes the "promotion"
  -- logic in determination impossible to corrupt by accident.
  constraint vendor_tin_present_ck check (keyed_by <> 'TIN' or tin_bidx is not null)
);

create index vendor_client_ix   on app.vendor (firm_id, client_id);
create index vendor_name_ix     on app.vendor (firm_id, client_id, name_norm);
create index vendor_tin_bidx_ix on app.vendor (firm_id, tin_bidx) where tin_bidx is not null;


-- -------------------------------------------------------------------------------------
-- ledger_line
--
-- One row per payment. ~1M rows.
--
-- NOTE WHAT IS ABSENT: there is no plaintext TIN column. Determination groups on
-- tin_bidx, which indexes and aggregates exactly like any other bytea, so a million rows
-- can be grouped by TIN without a single decryption.
--
-- IDENTITY ACROSS RE-IMPORTS is (firm_id, client_id, source_system, source_txn_id):
--   tier 1  native id from QuickBooks/Xero -- stable across re-exports AND across content
--           edits, which is exactly what a revised export needs: an amount correction must
--           read as an UPDATE to this row, not a delete plus an insert.
--   tier 2  'H:<hash>' synthesized for spreadsheets, which have no id column. The hash
--           covers the natural tuple plus a duplicate ordinal -- the ordinal matters
--           because two identical $50 cheques to the same plumber on the same day genuinely
--           happen, and a pure content hash would silently collapse them into one.
-- -------------------------------------------------------------------------------------
create table app.ledger_line (
  -- Identity column, but NOT the primary key on its own: the PK is (firm_id, id).
  -- One composite unique index then serves both purposes -- row identity and the target
  -- for child tables' composite foreign keys -- instead of maintaining two full indexes
  -- over a million rows. On a 500k-row load that is one fewer index write per row.
  id             bigint generated always as identity,
  firm_id        bigint      not null default app.current_firm_id(),
  client_id      bigint      not null,
  vendor_id      bigint,                   -- resolved during the merge

  source_system  text        not null,
  source_txn_id  text        not null,

  vendor_name_raw   text     not null,     -- as recorded; the explanation shows this
  vendor_name_norm  text     not null,
  name_norm_version smallint not null,

  tin_bidx       bytea,                    -- null when the TIN is missing or malformed
  tin_status     text        not null,
  tin_last4      char(4),

  payment_date   date        not null,
  -- Stored generated column: determination filters by year on every pass, and computing
  -- it here means the filter is an index-friendly equality rather than a function call
  -- over a million rows.
  tax_year       smallint    generated always as (extract(year from payment_date)::smallint) stored,

  amount_cents      bigint   not null,     -- negative for reversals and refunds
  withholding_cents bigint   not null default 0,

  method_canon      text     not null,
  is_card_or_tpso   boolean  not null,
  entry_type        text     not null default 'PAYMENT',
  reverses_source_txn_id text,
  expense_class     text     not null default 'SERVICES',
  currency          char(3)  not null default 'USD',
  memo              text,

  -- Covers every determination-relevant field. The merge compares it to decide whether a
  -- row actually changed, so an unchanged row costs zero heap writes, zero WAL, and zero
  -- dirty marks -- which is what makes "importing the same file twice changes nothing" a
  -- property of the storage engine rather than of application logic.
  row_hash       bytea       not null,

  first_seen_run_id   bigint not null,
  last_changed_run_id bigint not null,

  -- Soft delete only. A transmitted filing must be able to point at the evidence that
  -- produced it, even after the bookkeeper deletes the line from their books.
  deleted_at        timestamptz,
  deleted_by_run_id bigint,

  constraint ledger_pk         primary key (firm_id, id),
  constraint ledger_source_uk  unique (firm_id, client_id, source_system, source_txn_id),
  constraint ledger_client_fk  foreign key (firm_id, client_id) references app.client (firm_id, id),
  constraint ledger_vendor_fk  foreign key (firm_id, vendor_id) references app.vendor (firm_id, id),
  constraint ledger_tin_status_ck check (tin_status in ('PRESENT', 'MISSING', 'MALFORMED')),
  constraint ledger_entry_type_ck check (entry_type in ('PAYMENT', 'REVERSAL', 'REFUND', 'VOID')),
  constraint ledger_withholding_ck check (withholding_cents >= 0),
  constraint ledger_currency_ck    check (currency = 'USD')
);

-- The determination pass's main access path: one client's live rows for one year.
create index ledger_determination_ix on app.ledger_line (firm_id, client_id, tax_year)
  where deleted_at is null;

-- DELIBERATELY NOT INDEXED: (tin_bidx, vendor_name_norm) and vendor_id.
--
-- Both looked useful and are not. Determination reads an entire client-year through
-- ledger_determination_ix and then aggregates in memory, so an identity index is never
-- probed -- it would only ever be maintained. The per-vendor explainability query
-- ("show me this vendor's payments") is a single client's few thousand rows reached
-- through the same index, filtered in memory, by one human at a time.
--
-- Each index costs one write per row on a 500k-row load. Two speculative indexes cost
-- roughly a third of the merge budget to serve queries that never use them.


-- -------------------------------------------------------------------------------------
-- import_run -- one row per invocation, with phase timings.
--
-- Timings are persisted rather than only logged, because the brief asks for them and a
-- console line is not a durable artifact: after a 40-second import scrolls past, the
-- question "which phase was slow?" should still be answerable.
-- -------------------------------------------------------------------------------------
create table app.import_run (
  id             bigint generated always as identity primary key,
  firm_id        bigint      not null default app.current_firm_id(),
  started_at     timestamptz not null default clock_timestamp(),
  finished_at    timestamptz,
  state          text        not null default 'RUNNING',
  export_dir     text        not null,
  tax_year       smallint    not null,
  revision       int         not null default 0,

  rows_read      bigint not null default 0,
  rows_rejected  bigint not null default 0,
  rows_inserted  bigint not null default 0,
  rows_updated   bigint not null default 0,
  rows_unchanged bigint not null default 0,
  rows_tombstoned bigint not null default 0,
  duplicate_keys_collapsed bigint not null default 0,

  parse_ms       bigint,
  copy_ms        bigint,
  stage_index_ms bigint,
  dedupe_ms      bigint,
  merge_ms       bigint,
  tombstone_ms   bigint,
  vendor_ms      bigint,
  total_ms       bigint,

  rejection_summary jsonb not null default '{}'::jsonb,
  failure_reason text,

  constraint import_run_firm_id_uk unique (firm_id, id),
  constraint import_run_state_ck check (state in ('RUNNING','PARSED','COPIED','MERGED','COMPLETED','FAILED'))
);


-- -------------------------------------------------------------------------------------
-- import_file -- checksums of files we have seen.
--
-- Recorded, but deliberately NOT used to short-circuit. Skipping an import because the
-- checksum matches proves nothing; running it and proving the merge was a no-op proves
-- the invariant we actually care about.
-- -------------------------------------------------------------------------------------
create table app.import_file (
  id             bigint generated always as identity primary key,
  firm_id        bigint      not null default app.current_firm_id(),
  file_name      text        not null,
  sha256         text        not null,
  source_system  text        not null,
  row_count      bigint      not null,
  first_seen_run_id bigint   not null,
  last_seen_run_id  bigint   not null,
  seen_count     int         not null default 1,

  constraint import_file_firm_id_uk unique (firm_id, id),
  constraint import_file_sha_uk     unique (firm_id, file_name, sha256)
);


-- -------------------------------------------------------------------------------------
-- import_rejection -- the report for rows that could not be represented.
--
-- Written on a SEPARATE transaction from the merge, so the report survives even if the
-- merge aborts. If an import dies, that is precisely when you most want to know which
-- rows it choked on.
--
-- raw_line_redacted is TIN-masked before storage. A rejected row's raw text contains
-- whatever was in the TIN column, and for a sole proprietor that is an SSN. Rejection
-- reports are the classic place PII quietly accumulates.
-- -------------------------------------------------------------------------------------
create table app.import_rejection (
  id             bigint generated always as identity primary key,
  firm_id        bigint      not null default app.current_firm_id(),
  import_run_id  bigint      not null,
  file_name      text        not null,
  file_line_no   bigint      not null,
  reason_code    text        not null,
  column_name    text,
  reason_detail  text,
  raw_line_redacted text     not null,

  constraint import_rejection_firm_id_uk unique (firm_id, id)
);

create index import_rejection_run_ix on app.import_rejection (firm_id, import_run_id, reason_code);


-- -------------------------------------------------------------------------------------
-- determination_dirty_client -- what needs re-determining.
--
-- The unit is (firm, client, tax_year), and that grain is provably sufficient because
-- vendor identity never crosses clients (see app.vendor above). No payment row can affect
-- any vendor outside its own client, so marking the client is enough.
-- -------------------------------------------------------------------------------------
create table app.determination_dirty_client (
  firm_id        bigint      not null default app.current_firm_id(),
  client_id      bigint      not null,
  tax_year       smallint    not null,
  marked_at      timestamptz not null default clock_timestamp(),
  marked_by_run_id bigint,

  primary key (firm_id, client_id, tax_year)
);


-- -------------------------------------------------------------------------------------
-- The dirty-marking trigger.
--
-- STATEMENT-level with transition tables, not row-level, and deliberately a trigger
-- rather than a CTE inside the importer. Three reasons, in increasing order of
-- importance:
--
--   1. It fires ONCE PER STATEMENT. A 500k-row merge produces one set-based insert of a
--      few hundred distinct clients, not 500k trigger invocations.
--
--   2. It sees the PRE-IMAGE for free. A row whose date moves from 2025 to 2026 dirties
--      BOTH years; a row whose client changes dirties both clients. Hand-rolling that in
--      the importer is exactly where the bug would live.
--
--   3. It CANNOT BE FORGOTTEN. Any writer marks the affected clients dirty -- the
--      importer, a backfill migration, or a human running UPDATE in psql at 2am on
--      February 1. Dirty marking becomes a database invariant rather than a convention,
--      which is the same argument as RLS applied to a different problem.
-- -------------------------------------------------------------------------------------
create or replace function app.mark_determination_dirty() returns trigger
language plpgsql
as $$
begin
  insert into app.determination_dirty_client (firm_id, client_id, tax_year)
  select distinct firm_id, client_id, tax_year
    from (
      select firm_id, client_id, tax_year from changed_rows
    ) x
   where tax_year is not null
  on conflict (firm_id, client_id, tax_year)
    do update set marked_at = clock_timestamp();
  return null;
end
$$;

-- Separate triggers per operation because the transition-table name differs: INSERT has
-- only NEW, DELETE has only OLD, and UPDATE needs both so that moving a row between years
-- or clients dirties the place it left as well as the place it arrived.
create trigger ledger_line_dirty_ins
  after insert on app.ledger_line
  referencing new table as changed_rows
  for each statement execute function app.mark_determination_dirty();

create trigger ledger_line_dirty_del
  after delete on app.ledger_line
  referencing old table as changed_rows
  for each statement execute function app.mark_determination_dirty();

create or replace function app.mark_determination_dirty_upd() returns trigger
language plpgsql
as $$
begin
  insert into app.determination_dirty_client (firm_id, client_id, tax_year)
  select distinct firm_id, client_id, tax_year
    from (
      select firm_id, client_id, tax_year from old_rows
      union
      select firm_id, client_id, tax_year from new_rows
    ) x
   where tax_year is not null
  on conflict (firm_id, client_id, tax_year)
    do update set marked_at = clock_timestamp();
  return null;
end
$$;

create trigger ledger_line_dirty_upd
  after update on app.ledger_line
  referencing old table as old_rows new table as new_rows
  for each statement execute function app.mark_determination_dirty_upd();


-- -------------------------------------------------------------------------------------
-- Staging template.
--
-- Each import creates an UNLOGGED per-run copy of this shape, COPYs into it, then merges.
--
-- UNLOGGED, not TEMP:
--   * unlogged skips WAL for the bulk write, which is the large win, and is fully
--     justified because the table is rebuildable from the file -- crash-unsafety costs
--     nothing here;
--   * TEMP would be bound to one session, whereas an unlogged table survives a FAILED
--     import so a second connection can inspect what went wrong.
--
-- Per-run rather than one shared table: no cross-import contention, no bloat, DROP is
-- instant, and there is no index maintenance during COPY.
--
-- Every column is text. COPY must never reject a row for type reasons -- validation and
-- normalization happen in Java BEFORE the row reaches the stream, which is what makes
-- "a malformed row never kills the import" structural rather than a try/catch.
-- -------------------------------------------------------------------------------------
create table stg.ledger_line_template (
  client_ref     text,
  source_system  text,
  source_txn_id  text,
  vendor_name_raw text,
  vendor_name_norm text,
  name_norm_version smallint,
  tin_bidx       bytea,
  -- Name-derived blind index, computed in Java with the same HMAC key and a distinct
  -- ':name:' domain prefix. Carried in staging so the vendor upsert can key on
  -- coalesce(tin_bidx, name_bidx) without the database ever needing the key -- an
  -- unkeyed digest() in SQL would be both weaker and inconsistent with how identity is
  -- computed everywhere else.
  name_bidx      bytea,
  tin_status     text,
  tin_last4      char(4),
  tin_ct         bytea,
  tin_key_ver    smallint,
  tin_raw_masked text,
  payment_date   date,
  amount_cents   bigint,
  withholding_cents bigint,
  method_canon   text,
  is_card_or_tpso boolean,
  entry_type     text,
  reverses_source_txn_id text,
  expense_class  text,
  currency       char(3),
  memo           text,
  row_hash       bytea,
  file_line_no   bigint
);

-- The template itself holds no data and has no firm_id, so it is exempt from RLS. Per-run
-- staging tables inherit this shape and are likewise unprotected -- they are private to
-- one import, dropped at the end, and the firm_id is stamped by the INSERT ... SELECT that
-- moves rows into the real table, where the WITH CHECK policy validates it.
comment on table stg.ledger_line_template is
  'Shape template for per-run UNLOGGED COPY staging tables. Never holds data itself.';
