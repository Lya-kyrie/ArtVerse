ALTER TABLE manga_agent_runs
  ADD COLUMN IF NOT EXISTS last_progress_at TIMESTAMPTZ;

ALTER TABLE manga_agent_runs
  ADD COLUMN IF NOT EXISTS current_phase VARCHAR(32);

UPDATE manga_agent_runs
SET last_progress_at = COALESCE(updated_at, created_at, now()),
    current_phase = COALESCE(current_phase, 'MODEL')
WHERE last_progress_at IS NULL OR current_phase IS NULL;

ALTER TABLE manga_agent_runs
  ALTER COLUMN last_progress_at SET NOT NULL;

ALTER TABLE manga_agent_runs
  ALTER COLUMN current_phase SET NOT NULL;

ALTER TABLE manga_agent_runs
  ALTER COLUMN current_phase SET DEFAULT 'MODEL';

CREATE INDEX IF NOT EXISTS idx_manga_agent_runs_running_progress
  ON manga_agent_runs(status, last_progress_at)
  WHERE status = 'RUNNING';
