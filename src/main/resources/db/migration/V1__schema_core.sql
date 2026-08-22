-- =====================================================================================
-- V1 -- Core tenancy: the firm context function, then firm / client / app_user.
--
-- ORDER IS LOAD-BEARING. app.current_firm_id() must exist before the first CREATE
-- TABLE, because the app_force_rls event trigger (installed by db/setup.sql) fires on
-- CREATE TABLE and immediately writes a policy whose predicate calls this function.
--
-- The five isolation rules, applied without exception across every migration:
--   1. Every tenant table carries a NOT NULL firm_id.
--   2. Every firm_id is provably consistent with its parent via a COMPOSITE foreign
--      key (firm_id, parent_id) -> parent(firm_id, id). RLS answers "can you see it";
--      the composite FK answers "could it ever have been wrong".
--   3. Every unique constraint is prefixed with firm_id. A global unique index leaks
--      cross-firm existence through constraint-violation errors, which RLS does not
--      filter.
--   4. The runtime role is neither owner nor superuser, and every table is FORCE RLS.
--   5. Firm context is only ever set transaction-locally, never from user input.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- The firm context function.
--
-- STABLE, not VOLATILE: a stable function is evaluated once per statement, so
-- `firm_id = app.current_firm_id()` is usable as an index scan key. Marking it
-- volatile would force per-row evaluation and quietly defeat every index we build.
--
-- It RAISES rather than returning NULL. The NULL variant fails silently closed --
-- safe, but it produces a maddening failure mode where the dashboard is empty and
-- nothing appears in any log. Raising 28000 fails loudly closed: identical safety,
-- far better diagnosability, and something crisp for the test suite to assert.
-- -------------------------------------------------------------------------------------
create or replace function app.current_firm_id() returns bigint
language plpgsql
stable
parallel safe
as $$
declare
  v text;
begin
  v := current_setting('app.current_firm_id', true);
  if v is null or v = '' then
    raise exception 'firm context is not set on this transaction'
      using errcode = '28000',
            hint = 'open the transaction through FirmTransactionManager, or use FirmContext.runAs(...)';
  end if;
  return v::bigint;
end
$$;

comment on function app.current_firm_id() is
  'Transaction-local firm context. Raises 28000 when unset so that a missing context '
  'is a loud failure rather than a silently empty result set.';


-- -------------------------------------------------------------------------------------
-- firm -- the tenant registry.
--
-- Keyed by `id` rather than `firm_id`, so the event trigger does not auto-protect it and
-- its policies are written by hand.
--
-- A DELIBERATE ASYMMETRY, and the one place tenancy is relaxed:
--
--   SELECT is permitted to readiness_app for ALL rows.
--   INSERT / UPDATE / DELETE are permitted to NO ONE except readiness_owner.
--
-- The strict form -- `using (id = app.current_firm_id())` -- is what one reaches for
-- first, and it is unworkable: it creates a bootstrap paradox. Resolving a firm slug to
-- an id requires reading app.firm, which requires already knowing the firm id. Nothing
-- could ever provision a firm or look one up, and every entry point would need an
-- out-of-band way to learn its own id.
--
-- Relaxing SELECT is defensible because app.firm holds no tenant data: a slug, a display
-- name, and an IRS transmitter control code. Knowing that a firm named "Harborline CPA"
-- exists discloses nothing about its clients, vendors, payments, or filings -- all of
-- which remain strictly isolated by the firm_id policies on every other table. What is
-- protected is the data; what is readable is the directory.
--
-- Writes stay closed. Provisioning a tenant is an operational act performed by the
-- migration/owner role, never something the running application can do.
-- -------------------------------------------------------------------------------------
create table app.firm (
  id          bigint generated always as identity primary key,
  slug        text        not null,
  name        text        not null,
  tcc         text,                                   -- IRS transmitter control code (stubbed)
  created_at  timestamptz not null default now(),
  constraint firm_slug_uk unique (slug)
);

alter table app.firm enable row level security;
alter table app.firm force  row level security;

-- The application may read the directory, but may not modify it. There is deliberately
-- no INSERT/UPDATE/DELETE policy for readiness_app: under RLS a command with no
-- permissive policy affects zero rows, so writes fail closed even if someone were to
-- re-grant the privilege.
create policy firm_read on app.firm for select to readiness_app
  using (true);

-- The provisioning role. Needed because FORCE ROW LEVEL SECURITY applies to the table
-- owner too, so without this the migration that seeds the firms could not insert them.
create policy firm_provision on app.firm for all to readiness_owner
  using (true) with check (true);


-- -------------------------------------------------------------------------------------
-- client -- a business the firm files on behalf of.
--
-- `unique (firm_id, id)` looks redundant next to the primary key, but it is the target
-- of every child table's composite foreign key. Without it, rule 2 is unenforceable.
-- -------------------------------------------------------------------------------------
create table app.client (
  id            bigint generated always as identity primary key,
  firm_id       bigint      not null default app.current_firm_id() references app.firm(id),
  client_ref    text        not null,                 -- the id the bookkeeper uses
  legal_name    text        not null,
  -- The payer's own EIN. Encrypted like any other TIN: for a sole proprietor the
  -- payer EIN can be an SSN too.
  ein_ct        bytea,
  ein_key_ver   smallint,
  ein_last4     char(4),
  address_line1 text,
  address_line2 text,
  city          text,
  state_code    char(2),
  postal_code   text,
  created_at    timestamptz not null default now(),

  constraint client_firm_id_uk unique (firm_id, id),          -- composite FK target
  constraint client_ref_uk     unique (firm_id, client_ref)   -- rule 3: firm-prefixed
);

create index client_firm_name_ix on app.client (firm_id, legal_name);


-- -------------------------------------------------------------------------------------
-- app_user -- two roles, per the brief. Authentication itself is stubbed.
--
-- Note that role is an authorization concern INSIDE a firm. Isolation is not a role
-- concern: a FIRM_ADMIN of firm 1 has exactly as much visibility into firm 2 as an
-- anonymous request does, which is none.
-- -------------------------------------------------------------------------------------
create table app.app_user (
  id            bigint generated always as identity primary key,
  firm_id       bigint      not null default app.current_firm_id(),
  username      text        not null,
  display_name  text        not null,
  role          text        not null,
  created_at    timestamptz not null default now(),

  constraint app_user_firm_id_uk unique (firm_id, id),
  constraint app_user_name_uk    unique (firm_id, username),
  constraint app_user_role_ck    check (role in ('PREPARER', 'FIRM_ADMIN'))
);


-- -------------------------------------------------------------------------------------
-- reason_code -- a closed enum backed by a table, so the CLI, the Part 4 page, the
-- API and the tests all share one source of truth for human-readable text, and adding
-- a reason is a migration rather than a string literal buried in a template.
--
-- Deliberately NOT firm-scoped: it is reference data, identical for every firm, and
-- carries no tenant information. It is therefore exempt from RLS, and that exemption
-- is listed explicitly in FirmIsolationIT's allowlist so it can never be silent.
-- -------------------------------------------------------------------------------------
create table app.reason_code (
  code        text primary key,
  category    text not null,
  human_text  text not null,
  constraint reason_code_category_ck check (
    category in ('IMPORT_REJECTION', 'PAYMENT_DISPOSITION', 'DETERMINATION_EXCEPTION',
                 'ATTENTION', 'IRS_REJECTION')
  )
);

grant select on app.reason_code to readiness_app;
