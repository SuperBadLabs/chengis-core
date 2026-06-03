-- Prevent duplicate active queue entries for the same build.
-- Allows historical duplicates once an item is completed/dead-lettered.
CREATE UNIQUE INDEX IF NOT EXISTS idx_build_queue_active_build_id_uniq
ON build_queue (build_id)
WHERE status IN ('pending', 'dispatching', 'dispatched');
