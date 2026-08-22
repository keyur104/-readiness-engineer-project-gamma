-- =====================================================================================
-- V9 -- Credentials for the two roles.
--
-- THE BOOTSTRAP PARADOX, AND WHY IT NEEDS NO NEW MACHINERY.
--
-- Every read in this system requires a firm context, because app.current_firm_id() raises
-- 28000 rather than returning NULL. But the firm context is derived from the authenticated
-- user, and finding that user requires a read. The one query that establishes tenancy
-- cannot itself be scoped by tenancy.
--
-- The obvious fix is a SECURITY DEFINER function that looks the user up with the owner's
-- privileges. It is also WRONG HERE, and instructively so: every table in schema app is
-- FORCE ROW LEVEL SECURITY, which means the owner is subject to its own policies. A
-- definer function running as readiness_owner would hit the identical 28000. FORCE is
-- doing exactly what it was added for, including to the escape hatch someone reaches for.
--
-- The actual answer needs nothing new. Log-in is two ordinary reads:
--
--   1. app.firm, in a system transaction with no firm context. app.firm is the ONE table
--      whose SELECT policy is deliberately open (see V1/D39), precisely because resolving
--      a firm is what must happen before a firm context can exist.
--   2. app.app_user, inside FirmContext.runAs(that firm), under the normal policy.
--
-- So a firm's staff list stays confidential and firm-scoped, there is no SECURITY DEFINER
-- function anywhere in the schema, and the login path exercises the same tenancy
-- machinery as every other read rather than stepping around it.
-- =====================================================================================

alter table app.app_user add column password_hash text;
alter table app.app_user add column disabled      boolean not null default false;
alter table app.app_user add column last_login_at timestamptz;

-- Username is unique per firm, not globally (see app_user_name_uk in V1) -- two firms may
-- each employ a Sam. The login identifier is therefore 'username@firm-slug', which is not
-- a cosmetic choice: a globally-unique login name would quietly make one firm's namespace
-- depend on another's, and the first collision would land during onboarding.
create index app_user_login_ix on app.app_user (firm_id, username);


-- -------------------------------------------------------------------------------------
-- Development credentials.
--
-- Real bcrypt hashes of the documented development password, NOT a {noop} placeholder.
-- That matters for more than tidiness: {noop} in a seed is the thing that survives into
-- the first production deployment, because it works, and nothing fails until someone
-- reads the table. A genuine {bcrypt} value means the shipped default already has the
-- shape the real one needs, and because the encoder is a DelegatingPasswordEncoder,
-- moving to a stronger algorithm later is a data change rather than a code change.
--
-- The password is 'readiness-dev' and is published in the project write-up. It is a
-- fixture on a par with the readiness_app_dev database password: convenience for a
-- reviewer running this locally, and worthless anywhere else.
--
-- Seeded inside a per-firm context loop for the same reason V3 is: the policy binds every
-- role, so a bare UPDATE with no firm context would match zero rows and report success.
-- -------------------------------------------------------------------------------------
do $$
declare
  f record;
begin
  for f in select id, slug from app.firm order by id loop
    perform set_config('app.current_firm_id', f.id::text, true);

    update app.app_user u
       set password_hash = h.hash
      from (values
              ('dana',   '{bcrypt}$2a$10$E7rXLjkOC7jZYzHxDaHKceOAb2/TPJVJ2N6ksjg4Igy.W9V3DD3gi'),
              ('sam',    '{bcrypt}$2a$10$45fd.n74pDTDXu71TvRN0O/DvhAvOEXDHmUXAzhwKJvq7Id0nmUa2'),
              ('priya',  '{bcrypt}$2a$10$s.u0jP91VoUiQzQSno47TuvpoU0QCD7sHf6/Zp1DiK/6HCBgfdgaG'),
              ('jordan', '{bcrypt}$2a$10$jT3J7DMQPkGfRh1N7UUvJOL0Hk.WJXKZUMGbrqf1SiBD9m5GS39Em')
           ) as h(username, hash)
     where u.username = h.username
       and u.firm_id  = f.id;
  end loop;
end $$;
