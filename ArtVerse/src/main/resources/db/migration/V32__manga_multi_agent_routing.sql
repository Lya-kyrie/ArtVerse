UPDATE manga_agent_runs
SET route = CASE route
  WHEN 'CHAT' THEN 'CONVERSATION'
  WHEN 'AUTO' THEN 'DIRECTOR'
  WHEN 'HITL' THEN 'DIRECTOR'
  ELSE route
END;

ALTER TABLE manga_agent_runs
  DROP CONSTRAINT IF EXISTS ck_manga_agent_runs_route;

ALTER TABLE manga_agent_runs
  ADD CONSTRAINT ck_manga_agent_runs_route
  CHECK (route IN ('CONVERSATION', 'CREATIVE', 'STORYBOARD', 'REVIEW', 'DIRECTOR'));

ALTER TABLE manga_agent_runs
  ADD COLUMN IF NOT EXISTS route_source VARCHAR(32) NOT NULL DEFAULT 'FIXED',
  ADD COLUMN IF NOT EXISTS route_confidence DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS router_version VARCHAR(32),
  ADD COLUMN IF NOT EXISTS routing_decision_json TEXT;
