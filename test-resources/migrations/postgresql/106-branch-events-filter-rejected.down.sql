-- Revert the CHECK extension. If any 'filter_rejected' rows exist they
-- would violate the narrower constraint, so we delete them first — the
-- down migration is best-effort cleanup, not a guarantee.
DELETE FROM branch_events WHERE event_type = 'filter-rejected';
--;;
ALTER TABLE branch_events
  DROP CONSTRAINT IF EXISTS branch_events_event_type_chk;
--;;
ALTER TABLE branch_events
  ADD CONSTRAINT branch_events_event_type_chk
  CHECK (event_type IN ('created', 'updated', 'archived', 'resurrected'));
