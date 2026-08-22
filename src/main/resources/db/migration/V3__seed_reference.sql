-- =====================================================================================
-- V3 -- Reference data: the firms, their users, and the reason-code catalogue.
--
-- Firms are seeded by a migration rather than created by the application. Provisioning a
-- tenant is an operational act, and keeping it out of the running system means the app
-- role never needs write access to the tenant registry (see the policies in V1).
--
-- The brief specifies two firms, and authentication is explicitly out of scope, so this
-- is the whole of tenant setup.
-- =====================================================================================

insert into app.firm (slug, name, tcc) values
  ('northstar',  'Northstar CPA Group',  'NS001'),
  ('harborline', 'Harborline Tax Partners', 'HL001')
on conflict (slug) do nothing;


-- -------------------------------------------------------------------------------------
-- Users. Two roles, per the brief, and no more.
--
-- The split follows the irreversible actions: transmitting to the IRS and overriding a
-- filing state are what create legal and financial exposure, so those are what FIRM_ADMIN
-- gates. Everything else -- importing, determining, resolving exceptions, revealing a TIN
-- for a legitimate reason -- a PREPARER can do.
--
-- Note that role is an authorization concern INSIDE a firm. A FIRM_ADMIN of northstar has
-- exactly as much visibility into harborline as an anonymous request does, which is none.
-- Isolation is not a role concern.
-- -------------------------------------------------------------------------------------
-- NOTE the firm context calls below.
--
-- The firm_isolation policies apply to EVERY role, including readiness_owner, because
-- the tables are FORCE ROW LEVEL SECURITY and the auto-created policy has no `TO` clause.
-- That is deliberate: the owner gets no special treatment, so there is no privileged path
-- that could quietly write a row into the wrong firm.
--
-- The consequence is that a migration seeding firm-scoped data must establish firm
-- context exactly as the application does. Doing it per firm here is not a workaround --
-- it is the policy working, demonstrated in the migration itself.
do $$
declare
  f record;
begin
  for f in select id, slug from app.firm order by slug loop
    perform set_config('app.current_firm_id', f.id::text, true);

    insert into app.app_user (firm_id, username, display_name, role)
    select f.id, u.username, u.display_name, u.role
      from (values
              ('northstar',  'dana',   'Dana Whitfield', 'FIRM_ADMIN'),
              ('northstar',  'sam',    'Sam Okafor',     'PREPARER'),
              ('harborline', 'priya',  'Priya Raman',    'FIRM_ADMIN'),
              ('harborline', 'jordan', 'Jordan Lee',     'PREPARER')
           ) as u(firm_slug, username, display_name, role)
     where u.firm_slug = f.slug
    on conflict (firm_id, username) do nothing;
  end loop;

  -- Leave no firm context behind. The setting is transaction-local so Postgres would
  -- discard it at commit anyway, but clearing it explicitly means the rest of this
  -- migration cannot accidentally inherit the last firm in the loop.
  perform set_config('app.current_firm_id', '', true);
end $$;


-- -------------------------------------------------------------------------------------
-- reason_code -- one source of truth for human-readable text.
--
-- A table rather than an enum in code, so the CLI, the morning-after page, and the tests
-- all render the same words, and adding a reason is a migration a reviewer can see rather
-- than a string literal buried in a template.
-- -------------------------------------------------------------------------------------
insert into app.reason_code (code, category, human_text) values
  -- Import rejections: structural. The row cannot be represented at all.
  ('UNPARSEABLE_DATE',        'IMPORT_REJECTION', 'The date could not be read in this file''s format.'),
  ('UNPARSEABLE_AMOUNT',      'IMPORT_REJECTION', 'The amount is not a number.'),
  ('SUB_CENT_AMOUNT',         'IMPORT_REJECTION', 'The amount has sub-cent precision.'),
  ('MISSING_CLIENT_REF',      'IMPORT_REJECTION', 'No client reference.'),
  ('UNKNOWN_CLIENT_REF',      'IMPORT_REJECTION', 'The client reference is not in this export''s client list.'),
  ('UNIDENTIFIABLE_VENDOR',   'IMPORT_REJECTION', 'Neither a vendor name nor a TIN was recorded.'),
  ('RAGGED_ROW',              'IMPORT_REJECTION', 'The row does not match the file''s column structure.'),
  ('UNSUPPORTED_CURRENCY',    'IMPORT_REJECTION', 'Only USD is supported.'),
  ('MISSING_REQUIRED_COLUMN', 'IMPORT_REJECTION', 'A required column is missing from this file.'),
  ('DUPLICATE_KEY_IN_FILE',   'IMPORT_REJECTION', 'The same transaction id appeared twice in one file; the later copy was kept.'),

  -- Per-payment dispositions: why a payment did or did not count toward the threshold.
  -- These are what the explainability view renders next to each payment.
  ('COUNTED',                      'PAYMENT_DISPOSITION', 'Counted toward the threshold.'),
  ('COUNTED_REVERSAL',             'PAYMENT_DISPOSITION', 'Reversal or refund; reduces the year''s net.'),
  ('EXCLUDED_OUT_OF_TAX_YEAR',     'PAYMENT_DISPOSITION', 'Paid outside the tax year.'),
  ('EXCLUDED_VOID',                'PAYMENT_DISPOSITION', 'Voided before it settled; no money moved.'),
  ('EXCLUDED_NON_SERVICES',        'PAYMENT_DISPOSITION', 'Not for services, so not nonemployee compensation.'),
  ('EXCLUDED_CARD_TPSO',           'PAYMENT_DISPOSITION', 'Paid by card or third-party network; the processor reports this on Form 1099-K.'),
  ('EXCLUDED_ZERO_AMOUNT',         'PAYMENT_DISPOSITION', 'Zero amount.'),

  -- Determination exceptions: a person needs to act.
  ('MISSING_TIN',                'DETERMINATION_EXCEPTION', 'A form is required but no TIN was collected. A W-9 is needed before this can be filed.'),
  ('MALFORMED_TIN',              'DETERMINATION_EXCEPTION', 'The recorded TIN is not nine digits and cannot be used.'),
  ('AMBIGUOUS_VENDOR_IDENTITY',  'DETERMINATION_EXCEPTION', 'One vendor name is recorded under more than one TIN; a person must decide which payments belong together.'),
  ('NEGATIVE_REPORTABLE',        'DETERMINATION_EXCEPTION', 'Refunds exceed payments for the year, giving a negative reportable amount.'),
  ('UNKNOWN_PAYMENT_METHOD',     'DETERMINATION_EXCEPTION', 'The payment method was not recognised; it has been counted toward the threshold.'),
  ('INVALID_TIN_PREFIX',         'DETERMINATION_EXCEPTION', 'The TIN begins 000, which the IRS will reject.'),
  ('DETERMINATION_CHANGED_AFTER_FILING', 'DETERMINATION_EXCEPTION', 'Newly imported data changes a vendor whose filing has already been transmitted.')
on conflict (code) do update
   set category = excluded.category,
       human_text = excluded.human_text;
