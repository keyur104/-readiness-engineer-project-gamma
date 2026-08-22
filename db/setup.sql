-- =====================================================================================
-- One-time bootstrap. Run ONCE as a superuser:
--
--   psql -h localhost -p 5432 -U postgres -f db/setup.sql
--
-- Creates the role split that makes Row-Level Security actually work, and the two
-- databases (runtime + test). Everything after this runs as a non-superuser.
--
-- WHY THREE ROLES (this is the part people get wrong):
--
--   postgres          superuser. Used here and never again. Superusers bypass RLS.
--   readiness_owner   owns the tables. Flyway migrations only. NOT a superuser and
--                     explicitly NOBYPASSRLS.
--   readiness_app     the application, the CLI, AND the test suite. Owns nothing.
--                     A test suite that connects as a superuser proves nothing at
--                     all, because RLS is bypassed for it.
--
-- Table owners are exempt from their own RLS policies unless the table is declared
-- FORCE ROW LEVEL SECURITY (see V7__rls_policies.sql). We do both: a non-owner
-- runtime role AND FORCE, so neither mistake alone is sufficient to leak data.
-- =====================================================================================

\set ON_ERROR_STOP on

-- Override at the command line with:  psql -v owner_pw='...' -v app_pw='...' -f db/setup.sql
\if :{?owner_pw} \else \set owner_pw 'readiness_owner_dev' \endif
\if :{?app_pw}   \else \set app_pw   'readiness_app_dev'   \endif

-- ---------------------------------------------------------------------------------
-- Roles
--
-- Built with format() + \gexec rather than a DO block, because psql does not interpolate
-- :'variables' inside dollar-quoted strings -- the block body is opaque to it. format()
-- with %L also quotes the password correctly, so a password containing a quote character
-- cannot break out of the statement.
-- ---------------------------------------------------------------------------------
select format('create role readiness_owner login password %L nosuperuser nocreatedb nocreaterole nobypassrls', :'owner_pw')
 where not exists (select 1 from pg_roles where rolname = 'readiness_owner') \gexec

select format('alter role readiness_owner login password %L nosuperuser nocreatedb nocreaterole nobypassrls', :'owner_pw')
 where exists (select 1 from pg_roles where rolname = 'readiness_owner') \gexec

select format('create role readiness_app login password %L nosuperuser nocreatedb nocreaterole nobypassrls noinherit', :'app_pw')
 where not exists (select 1 from pg_roles where rolname = 'readiness_app') \gexec

select format('alter role readiness_app login password %L nosuperuser nocreatedb nocreaterole nobypassrls noinherit', :'app_pw')
 where exists (select 1 from pg_roles where rolname = 'readiness_app') \gexec

-- ---------------------------------------------------------------------------------
-- Databases (CREATE DATABASE cannot run inside a transaction block)
-- ---------------------------------------------------------------------------------
select 'create database readiness owner readiness_owner'
 where not exists (select 1 from pg_database where datname = 'readiness') \gexec

select 'create database readiness_test owner readiness_owner'
 where not exists (select 1 from pg_database where datname = 'readiness_test') \gexec

-- ---------------------------------------------------------------------------------
-- Per-database setup. Identical for both; the test database is a full peer so that
-- the test suite exercises the real policies, the real grants, and the real role.
-- ---------------------------------------------------------------------------------
\connect readiness

revoke all on schema public from public;
revoke all on database readiness from public;
grant connect, temporary on database readiness to readiness_app;
grant connect, temporary on database readiness to readiness_owner;

create schema if not exists app       authorization readiness_owner;
create schema if not exists irs_stub  authorization readiness_owner;
-- Transient COPY staging lives in its own schema. The runtime role gets CREATE here and
-- ONLY here: it must be able to make per-run staging tables, but granting CREATE on the
-- tenant schema would let the application add objects alongside the real data, which is a
-- privilege surface with no upside. Staging holds no tenant rows -- firm_id is stamped by
-- the INSERT ... SELECT that moves rows into app.ledger_line, where WITH CHECK validates it.
create schema if not exists stg       authorization readiness_owner;

grant usage on schema app      to readiness_app;
grant usage on schema irs_stub to readiness_app;
grant usage, create on schema stg to readiness_app;

alter role readiness_app   in database readiness set search_path = app, public;
alter role readiness_owner in database readiness set search_path = app, public;

-- The RLS event trigger in V7 requires superuser to create, so it is bootstrapped
-- here rather than in a migration. It auto-enables RLS on any new table in schema
-- `app` that carries a firm_id column, turning "the developer forgot" into an
-- impossibility. FirmIsolationIT still asserts the outcome independently.
create or replace function app.auto_enable_rls() returns event_trigger
language plpgsql as $$
declare
  r record;
  tbl text;
begin
  for r in select * from pg_event_trigger_ddl_commands()
            where command_tag = 'CREATE TABLE' and schema_name = 'app'
  loop
    tbl := r.object_identity;
    if exists (select 1 from information_schema.columns c
                where c.table_schema = 'app'
                  and c.table_name = split_part(tbl, '.', 2)
                  and c.column_name = 'firm_id') then
      execute format('alter table %s enable row level security', tbl);
      execute format('alter table %s force  row level security', tbl);
      if not exists (select 1 from pg_policies p
                      where p.schemaname = 'app'
                        and p.tablename = split_part(tbl, '.', 2)
                        and p.policyname = 'firm_isolation') then
        execute format(
          'create policy firm_isolation on %s for all '
          'using (firm_id = app.current_firm_id()) '
          'with check (firm_id = app.current_firm_id())', tbl);
      end if;
    end if;
  end loop;
end $$;

alter function app.auto_enable_rls() owner to readiness_owner;

drop event trigger if exists app_force_rls;
create event trigger app_force_rls on ddl_command_end
  when tag in ('CREATE TABLE') execute function app.auto_enable_rls();

-- ---------------------------------------------------------------------------------
\connect readiness_test

revoke all on schema public from public;
revoke all on database readiness_test from public;
grant connect, temporary on database readiness_test to readiness_app;
grant connect, temporary on database readiness_test to readiness_owner;

create schema if not exists app       authorization readiness_owner;
create schema if not exists irs_stub  authorization readiness_owner;
-- Transient COPY staging lives in its own schema. The runtime role gets CREATE here and
-- ONLY here: it must be able to make per-run staging tables, but granting CREATE on the
-- tenant schema would let the application add objects alongside the real data, which is a
-- privilege surface with no upside. Staging holds no tenant rows -- firm_id is stamped by
-- the INSERT ... SELECT that moves rows into app.ledger_line, where WITH CHECK validates it.
create schema if not exists stg       authorization readiness_owner;

grant usage on schema app      to readiness_app;
grant usage on schema irs_stub to readiness_app;
grant usage, create on schema stg to readiness_app;

alter role readiness_app   in database readiness_test set search_path = app, public;
alter role readiness_owner in database readiness_test set search_path = app, public;

create or replace function app.auto_enable_rls() returns event_trigger
language plpgsql as $$
declare
  r record;
  tbl text;
begin
  for r in select * from pg_event_trigger_ddl_commands()
            where command_tag = 'CREATE TABLE' and schema_name = 'app'
  loop
    tbl := r.object_identity;
    if exists (select 1 from information_schema.columns c
                where c.table_schema = 'app'
                  and c.table_name = split_part(tbl, '.', 2)
                  and c.column_name = 'firm_id') then
      execute format('alter table %s enable row level security', tbl);
      execute format('alter table %s force  row level security', tbl);
      if not exists (select 1 from pg_policies p
                      where p.schemaname = 'app'
                        and p.tablename = split_part(tbl, '.', 2)
                        and p.policyname = 'firm_isolation') then
        execute format(
          'create policy firm_isolation on %s for all '
          'using (firm_id = app.current_firm_id()) '
          'with check (firm_id = app.current_firm_id())', tbl);
      end if;
    end if;
  end loop;
end $$;

alter function app.auto_enable_rls() owner to readiness_owner;

drop event trigger if exists app_force_rls;
create event trigger app_force_rls on ddl_command_end
  when tag in ('CREATE TABLE') execute function app.auto_enable_rls();

\echo ''
\echo 'Bootstrap complete.'
\echo '  databases : readiness, readiness_test'
\echo '  roles     : readiness_owner (migrations), readiness_app (runtime + tests)'
\echo ''
\echo 'Next: mvn flyway:migrate  (or just run the app -- Flyway runs at startup)'
\echo ''
