ALTER TABLE manga_agent_runs
    ADD COLUMN IF NOT EXISTS result_schema VARCHAR(128),
    ADD COLUMN IF NOT EXISTS verified_result_json JSONB,
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;
