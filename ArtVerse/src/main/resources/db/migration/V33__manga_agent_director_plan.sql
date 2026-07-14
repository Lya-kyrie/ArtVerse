-- V33: Add execution_plan_json column for Director multi-step orchestration
ALTER TABLE manga_agent_runs ADD COLUMN IF NOT EXISTS execution_plan_json TEXT;
