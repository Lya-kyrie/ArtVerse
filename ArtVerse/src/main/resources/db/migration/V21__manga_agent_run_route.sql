ALTER TABLE manga_agent_runs
  ADD COLUMN IF NOT EXISTS route VARCHAR(32);

UPDATE manga_agent_runs
SET route = CASE
  WHEN status = 'WAITING_USER' THEN 'HITL'
  ELSE 'DIRECTOR'
END
WHERE route IS NULL;

ALTER TABLE manga_agent_runs
  ALTER COLUMN route SET DEFAULT 'DIRECTOR';

ALTER TABLE manga_agent_runs
  ALTER COLUMN route SET NOT NULL;

ALTER TABLE manga_agent_runs
  DROP CONSTRAINT IF EXISTS ck_manga_agent_runs_route;

ALTER TABLE manga_agent_runs
  ADD CONSTRAINT ck_manga_agent_runs_route
  CHECK (route IN ('DIRECTOR', 'HITL', 'REVIEW'));
