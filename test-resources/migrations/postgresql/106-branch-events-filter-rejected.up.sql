-- Extend `branch_events.event_type` vocabulary to include 'filter_rejected'
-- (CHG-FEAT-003 PR5 — trigger-time glob enforcement).
--
-- Why: when an operator narrows the multibranch `:include` (or expands
-- `:exclude`) between discovery and trigger time, an already-discovered
-- branch can fail the live filter. PR5 adds a per-trigger gate that
-- rejects such builds; this rejection MUST land in the branch-lifecycle
-- audit log so operators can answer "why didn't this branch build?".
-- The existing CHECK constraint only permits the four reconcile-time
-- transitions; rejection is a fifth, semantically distinct event.
--
-- Postgres ALTER path: DROP + ADD the named CHECK constraint in place.
-- No row rewrite (constraint metadata only); table stays online for
-- concurrent reads. The named constraint matches the original migration
-- 102 declaration so this is a clean swap.
ALTER TABLE branch_events
  DROP CONSTRAINT IF EXISTS branch_events_event_type_chk;
--;;
ALTER TABLE branch_events
  ADD CONSTRAINT branch_events_event_type_chk
  CHECK (event_type IN ('created', 'updated', 'archived', 'resurrected', 'filter-rejected'));
