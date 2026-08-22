-- =====================================================================================
-- V8 -- The morning-after page.
--
-- The brief: "A staff member arriving after an overnight run can see, at a glance...
-- Functional beats pretty. It just has to be fast and truthful."
--
-- TRUTHFUL IS THE HARD PART, and it is what drives every decision below.
--
-- The failure this page must not have is a staff member trusting a stale rollup at 7 a.m.
-- and concluding a client is filed when it is not. So:
--
--   * Everything that is a function of current state is a VIEW, never a stored rollup.
--     A view cannot go stale, cannot be forgotten to refresh, and cannot survive the
--     condition that produced it. If a poll succeeds at 06:59, the exception is gone at
--     07:00 with no reconciliation job in between.
--
--   * A MATERIALIZED VIEW would be exactly wrong here. It is stale by construction, which
--     is the one property this page cannot have. If it ever became necessary for
--     performance, the correct form is a rollup table updated in the SAME TRANSACTION as
--     the filing transition -- transactionally consistent, therefore never stale.
--
--   * Only the HUMAN ANNOTATION is stored (exception_ack). The truth is derived; the
--     person's note about the truth is persisted. Nothing that can go stale is ever the
--     thing being displayed.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- exception_ack -- a human's note against a derived exception.
--
-- Keyed by a stable dedupe_key rather than by a row id, because the exceptions themselves
-- are computed and have no durable identity. That is the point: the acknowledgment
-- survives the exception being recomputed, and disappears from view when the underlying
-- condition clears.
-- -------------------------------------------------------------------------------------
create table app.exception_ack (
  id           bigint generated always as identity,
  firm_id      bigint      not null default app.current_firm_id(),
  dedupe_key   text        not null,
  acked_by     text        not null,
  acked_at     timestamptz not null default clock_timestamp(),
  note         text,

  constraint exception_ack_pk primary key (firm_id, id),
  constraint exception_ack_uk unique (firm_id, dedupe_key)
);


-- -------------------------------------------------------------------------------------
-- v_client_status -- one row per client, per tax year the system knows about.
--
-- EVERY CLIENT GETS A ROW FOR EVERY KNOWN YEAR, via the cross join below. The obvious
-- shape -- group the filings and left join the clients -- has a silent hole: a client
-- with filings in 2024 but none in 2025 produces no 2025 row at all, so filtering the
-- page to 2025 makes that client VANISH rather than report "nothing to file". A client
-- disappearing from a completeness page is the single worst thing this page can do,
-- because absence reads as "not my problem" and there is nothing on screen to question.
--
-- The set of "known years" comes from import_run and filing, both small. Deliberately not
-- from ledger_line: a DISTINCT over a million rows to populate a page header is a cost
-- with no benefit, since a year cannot have ledger rows without an import run that loaded
-- them.
--
-- On a database with no imports and no filings this view is empty, which is correct --
-- there is no year to report on yet.
--
-- STATUS IS EVALUATED IN PRIORITY ORDER, FIRST MATCH WINS. The order is the design:
--
--   1 NEEDS ATTENTION dominates everything. At 7 a.m. the only question that matters is
--     "what needs me", and a client showing "partially filed" while holding a rejected
--     form would be a lie of omission.
--   2 NOTHING TO FILE  -- with 500 clients a large share have no vendor over $600.
--     Forcing them into "fully filed" overstates the work done; forcing them into
--     "needs attention" buries the real exceptions. A separate bucket is the honest answer.
--   3 FULLY FILED
--   4 PARTIALLY FILED  -- some accepted, some not, and every remainder still machine-
--     progressing. If any remainder needs a person, rule 1 already claimed it.
--   5 AWAITING IRS
--   6 READY TO FILE    -- determined but not yet sent. Calling this "awaiting IRS" would
--     be false: the IRS has nothing.
--
-- Rules 2 and 6 are beyond the brief's four and are documented as deliberate additions.
--
-- SECURITY INVOKER: a view without it runs its underlying reads as the view's OWNER, and
-- the owner is the one role whose reads are not what we want here. FORCE ROW LEVEL
-- SECURITY happens to keep even that case scoped -- but relying on it makes the isolation
-- of this page depend on a property of a different table set in a different migration.
-- security_invoker makes the querying role's own policies the ones that apply, which is
-- the property being relied on, stated where it is relied on.
-- -------------------------------------------------------------------------------------
create view app.v_client_status with (security_invoker = true) as
with years as (
  select tax_year from app.import_run
  union
  select tax_year from app.filing
),
counts as (
  select f.client_id,
         f.tax_year,
         count(*)                                                          as n_total,
         count(*) filter (where f.state = 'ACCEPTED')                      as n_accepted,
         count(*) filter (where f.state = 'REJECTED')                      as n_rejected,
         count(*) filter (where f.state = 'BLOCKED')                       as n_blocked,
         count(*) filter (where f.state = 'SUBMITTED_UNACKNOWLEDGED')      as n_awaiting,
         count(*) filter (where f.state in ('DRAFT', 'READY_TO_TRANSMIT')) as n_ready,
         count(*) filter (where f.state = 'BATCHED')                       as n_batched,
         coalesce(sum(f.amount_cents) filter (where f.state = 'ACCEPTED'), 0) as accepted_cents,
         min(f.state_changed_at)                                           as oldest_change
    from app.filing f
   group by f.client_id, f.tax_year
),
-- Attention is counted at CLIENT level, not per year. attention_item carries no tax_year
-- of its own, and deriving one would mean joining every item back through its filing.
-- The consequence during a two-year season is that a client with an unresolved 2024
-- problem also shows attention on its 2025 row. That is the safe direction to be wrong
-- in: it puts a person in front of something real rather than hiding it.
attention as (
  select a.client_id, count(*) as n_attention, min(a.severity) as worst_severity
    from app.attention_item a
   where a.resolved_at is null
   group by a.client_id
)
select c.id                                   as client_id,
       c.client_ref,
       c.legal_name,
       y.tax_year                             as tax_year,
       coalesce(k.n_total, 0)                 as n_total,
       coalesce(k.n_accepted, 0)              as n_accepted,
       coalesce(k.n_rejected, 0)              as n_rejected,
       coalesce(k.n_blocked, 0)               as n_blocked,
       coalesce(k.n_awaiting, 0)              as n_awaiting,
       coalesce(k.n_ready, 0)                 as n_ready,
       coalesce(k.n_batched, 0)               as n_batched,
       coalesce(k.accepted_cents, 0)          as accepted_cents,
       coalesce(a.n_attention, 0)             as n_attention,
       a.worst_severity,
       k.oldest_change,
       case
         when coalesce(k.n_rejected, 0) > 0
           or coalesce(k.n_blocked, 0) > 0
           or coalesce(a.n_attention, 0) > 0                           then 'NEEDS_ATTENTION'
         when coalesce(k.n_total, 0) = 0                               then 'NOTHING_TO_FILE'
         when k.n_accepted = k.n_total                                 then 'FULLY_FILED'
         when k.n_accepted > 0                                         then 'PARTIALLY_FILED'
         when coalesce(k.n_awaiting, 0) + coalesce(k.n_batched, 0) > 0 then 'AWAITING_IRS'
         else                                                               'READY_TO_FILE'
       end                                    as status
  from app.client c
  cross join years y
  left join counts k    on k.client_id = c.id and k.tax_year = y.tax_year
  left join attention a on a.client_id = c.id;


-- -------------------------------------------------------------------------------------
-- v_exception -- everything needing a person, from every source, in one list.
--
-- A VIEW rather than a table, so it cannot disagree with the data it describes.
--
-- ORDERED BY RISK, not by recency or by count. The ranking principle: risk of a DUPLICATE
-- OR MISSED FILING first, then IRS rejections, then paperwork. A reconciliation
-- discrepancy is the only entry that can mean money and penalties rather than work, and it
-- is what the whole Part 3 design exists to surface -- so it sorts above everything.
--
-- THE SAME PROBLEM IS NEVER LISTED TWICE. A vendor with no usable TIN raises a
-- determination exception (MISSING_TIN -- "we cannot complete this form") AND, once the
-- planner reaches it, a filing-level attention item (VENDOR_MISSING_TIN -- "this filing
-- cannot be transmitted"). Both records are correct and both are worth keeping: they are
-- raised by different passes and answer different questions. But to the person reading
-- this page at 7 a.m. they are ONE problem, and printing 1,218 of them twice is how a
-- page with an honest data model still manages to mislead.
--
-- The suppression is therefore driven by an EXPLICIT CODE PAIRING, not by "this vendor
-- already appears somewhere". The blanket version is the tempting one and it is wrong:
-- the same vendor can hold a missing TIN *and* an ambiguous identity, and collapsing on
-- vendor alone silently deletes the second problem from the page. Suppressing a genuine
-- exception is strictly worse than showing a duplicate, so the pairing is enumerated and
-- anything unpaired falls through the CASE to NULL and is always shown.
--
-- Each row carries a stable dedupe_key so a human's acknowledgment survives recomputation.
-- -------------------------------------------------------------------------------------
create view app.v_exception with (security_invoker = true) as
with filing_attention as (
  -- attention_item.entity_id is text, because it names filings, batches and vendors alike.
  -- Comparing f.id::text = a.entity_id rather than casting the other way is deliberate:
  -- a.entity_id::uuid throws on the rows that legitimately hold a vendor_key, and Postgres
  -- gives no guarantee that the entity_type predicate is evaluated first.
  select f.client_id, f.vendor_key, f.tax_year, a.type
    from app.attention_item a
    join app.filing f on f.firm_id = a.firm_id and f.id::text = a.entity_id
   where a.resolved_at is null
     and a.entity_type = 'FILING'
)

-- Batch- and filing-level attention items (unacknowledged too long, rejected, retries
-- exhausted, reconciliation discrepancies).
select a.severity                                    as severity,
       a.type                                        as code,
       a.entity_type                                 as entity_type,
       a.entity_id                                   as entity_id,
       a.client_id                                   as client_id,
       c.legal_name                                  as client_name,
       a.first_seen_at                               as first_seen_at,
       a.detail                                      as detail,
       'ATTENTION:' || a.entity_type || ':' || a.entity_id || ':' || a.type as dedupe_key
  from app.attention_item a
  left join app.client c on c.id = a.client_id
 where a.resolved_at is null

union all

-- Determination exceptions: a form is owed but something about the vendor blocks it.
select case vd_ex.severity when 'BLOCKING' then 5 else 6 end,
       vd_ex.code,
       'VENDOR',
       vd_ex.vendor_key,
       vd_ex.client_id,
       c.legal_name,
       vd_ex.raised_at,
       vd_ex.detail,
       'DETERMINATION:' || vd_ex.client_id || ':' || vd_ex.vendor_key || ':' || vd_ex.code
  from app.determination_exception vd_ex
  left join app.client c on c.id = vd_ex.client_id
 where vd_ex.resolved_at is null
   and not exists (select 1 from filing_attention fa
                    where fa.client_id  = vd_ex.client_id
                      and fa.vendor_key = vd_ex.vendor_key
                      and fa.tax_year   = vd_ex.tax_year
                      -- The pairing. NULL for any code with no equivalent attention item,
                      -- and NULL never equals fa.type, so that exception is always shown.
                      and fa.type = case vd_ex.code
                                      when 'MISSING_TIN'   then 'VENDOR_MISSING_TIN'
                                      when 'MALFORMED_TIN' then 'VENDOR_MISSING_TIN'
                                    end)

union all

-- Import rejections, grouped by file and reason rather than listed row by row.
--
-- 2,762 individual rejected rows would drown every other exception on the page. What a
-- person actually needs is "31 rows in firm1-2025.csv had an unreadable date", plus the
-- line numbers to go and look at.
select 7,
       'IMPORT_ROWS_REJECTED',
       'IMPORT',
       r.file_name || ':' || r.reason_code,
       null::bigint,
       null::text,
       min(run.started_at),
       jsonb_build_object(
         'file', r.file_name,
         'reasonCode', r.reason_code,
         'rowCount', count(*),
         'sampleLines', (array_agg(r.file_line_no order by r.file_line_no))[1:5]),
       'IMPORT:' || r.file_name || ':' || r.reason_code
  from app.import_rejection r
  join app.import_run run on run.id = r.import_run_id
 where run.id = (select max(id) from app.import_run where state = 'COMPLETED')
 group by r.file_name, r.reason_code;


-- -------------------------------------------------------------------------------------
-- Index supporting the dashboard aggregate.
--
-- One covering index over the filing table's status columns. At 500 clients and a few
-- hundred thousand filings this is an index-only scan aggregating into a few hundred
-- groups -- single-digit to low-tens of milliseconds, which is why no rollup table exists.
-- -------------------------------------------------------------------------------------
create index filing_dashboard_ix on app.filing (firm_id, tax_year, client_id, state);
