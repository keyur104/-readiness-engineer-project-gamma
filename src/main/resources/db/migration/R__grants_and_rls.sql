-- =====================================================================================
-- REPEATABLE migration -- grants, and the belt-and-braces RLS sweep.
--
-- WHY REPEATABLE (R__) RATHER THAN VERSIONED (V__):
--
-- Grants and policies have to apply to *every* table, so they must run after the last
-- CREATE TABLE. A versioned migration pinned to a number would need renumbering every
-- time a new schema migration is added, and would silently miss tables added after it.
-- Flyway runs repeatable migrations last, on every migrate, after all versioned ones.
-- That is exactly the semantics this file wants, and it makes the ordering problem
-- structurally impossible rather than a thing to remember.
--
-- Everything here is idempotent, so re-running on an unchanged schema is a no-op.
--
-- This file is the second line of defence. The first is the app_force_rls event trigger
-- installed by db/setup.sql, which protects tables at CREATE TABLE time. The third is
-- RlsGuard, which refuses to boot if any of it failed. Three independent mechanisms,
-- because every way of breaking RLS is silent.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Schema usage
-- -------------------------------------------------------------------------------------
grant usage on schema app      to readiness_app;
grant usage on schema irs_stub to readiness_app;

-- The runtime role gets CREATE only in stg, where per-run COPY staging tables live. It
-- owns the tables it creates there, so it needs no further grant on them -- but it does
-- need SELECT on the owner-created template, because
-- CREATE TABLE ... (LIKE stg.ledger_line_template) reads that table's definition.
grant usage, create on schema stg to readiness_app;
grant select on all tables in schema stg to readiness_app;
alter default privileges for role readiness_owner in schema stg
  grant select on tables to readiness_app;


-- -------------------------------------------------------------------------------------
-- Table and sequence privileges.
--
-- Note what is NOT granted:
--   TRUNCATE  -- is NOT filtered by row-level security. A role holding it can destroy
--                every firm's rows in one statement regardless of any policy. This is
--                the single most dangerous privilege in a multi-tenant Postgres schema
--                and the easiest to hand out by reflex with GRANT ALL.
--   REFERENCES -- would let the app role create foreign keys, and FK checks run as the
--                table owner without RLS, which is an existence-disclosure channel.
--   TRIGGER    -- would let the app role attach code that runs with owner privileges.
-- -------------------------------------------------------------------------------------
grant select, insert, update, delete on all tables    in schema app to readiness_app;
grant usage, select                  on all sequences in schema app to readiness_app;

grant select, insert, update, delete on all tables    in schema irs_stub to readiness_app;
grant usage, select                  on all sequences in schema irs_stub to readiness_app;

revoke truncate on all tables in schema app      from readiness_app;
revoke truncate on all tables in schema irs_stub from readiness_app;

-- Future tables created by the owner inherit the same shape, so a new migration cannot
-- accidentally leave the app role without access -- or with too much of it.
alter default privileges for role readiness_owner in schema app
  grant select, insert, update, delete on tables to readiness_app;
alter default privileges for role readiness_owner in schema app
  grant usage, select on sequences to readiness_app;

alter default privileges for role readiness_owner in schema irs_stub
  grant select, insert, update, delete on tables to readiness_app;
alter default privileges for role readiness_owner in schema irs_stub
  grant usage, select on sequences to readiness_app;


-- -------------------------------------------------------------------------------------
-- RLS sweep.
--
-- Re-applies ENABLE + FORCE and the firm_isolation policy to every firm-scoped table in
-- schema app. Redundant when the event trigger fired, which is the point: if the trigger
-- was ever dropped, or a table was created by some path that did not fire it, this
-- closes the gap on the next migrate rather than leaving the table quietly readable
-- across firms.
--
-- FORCE is the line people omit. Without it the table OWNER is exempt from its own
-- policies, so the entire design evaporates the moment anything runs as the owner --
-- a migration, a data fix, a psql session, a misconfigured DataSource.
-- -------------------------------------------------------------------------------------
do $$
declare
  t record;
begin
  for t in
    select c.relname
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'app'
       and c.relkind = 'r'
       and exists (select 1 from information_schema.columns col
                    where col.table_schema = 'app'
                      and col.table_name = c.relname
                      and col.column_name = 'firm_id')
     order by c.relname
  loop
    execute format('alter table app.%I enable row level security', t.relname);
    execute format('alter table app.%I force  row level security', t.relname);

    if not exists (select 1 from pg_policies p
                    where p.schemaname = 'app'
                      and p.tablename = t.relname
                      and p.policyname = 'firm_isolation') then
      execute format(
        'create policy firm_isolation on app.%I for all '
        'using (firm_id = app.current_firm_id()) '
        'with check (firm_id = app.current_firm_id())', t.relname);
      raise notice 'rls: created missing firm_isolation policy on app.%', t.relname;
    end if;
  end loop;
end $$;


-- -------------------------------------------------------------------------------------
-- The audit log's own hardening lives in V10, NOT here, and that placement was a bug
-- before it was a decision.
--
-- It was originally in this file, guarded on the table existing. It never ran. A
-- repeatable migration re-executes only when ITS OWN checksum changes -- so V10 created
-- app.audit_event in one migrate, this file was unchanged and did not re-run, and the
-- table sat there with the generic firm_isolation policy instead of the append-only one.
-- Nothing failed. The revoke of UPDATE and DELETE simply was not applied.
--
-- The general lesson: a repeatable migration is the right place for a SWEEP over whatever
-- exists, and the wrong place for hardening that one specific table needs. Table-specific
-- policy belongs in the migration that creates the table, where it is applied exactly
-- once, at the moment the table comes into being, and cannot be skipped.
-- -------------------------------------------------------------------------------------


-- -------------------------------------------------------------------------------------
-- The IRS stub's own books.
--
-- Deliberately NOT firm-scoped and deliberately NOT under RLS: this schema models an
-- external system that is not part of our tenancy model at all. The stub is the
-- independent oracle the tests judge against -- if our isolation rules applied to its
-- tables too, an assertion about what the "IRS" actually recorded would be filtered by
-- the very mechanism it is meant to audit.
--
-- An ArchUnit rule forbids any reference to the irs_stub schema outside the stub
-- package, so the seam stays real rather than merely intended.
-- -------------------------------------------------------------------------------------
