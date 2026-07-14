ALTER TABLE manga_agent_runs
    ADD COLUMN IF NOT EXISTS run_attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb;
