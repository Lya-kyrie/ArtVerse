UPDATE manga_agent_runs
SET route_source = CASE route_source
  WHEN 'RESUME' THEN 'RESUME_FIXED'
  WHEN 'FIXED' THEN 'AUTO'
  WHEN 'AGENT' THEN 'AUTO'
  ELSE route_source
END;

ALTER TABLE manga_agent_runs
  ALTER COLUMN route_source SET DEFAULT 'AUTO';

ALTER TABLE manga_agent_runs
  DROP CONSTRAINT IF EXISTS ck_manga_agent_runs_route_source;

ALTER TABLE manga_agent_runs
  ADD CONSTRAINT ck_manga_agent_runs_route_source
  CHECK (route_source IN ('AUTO', 'RESUME_FIXED', 'RESUME_RECLASSIFIED', 'SHADOW', 'FALLBACK'));
