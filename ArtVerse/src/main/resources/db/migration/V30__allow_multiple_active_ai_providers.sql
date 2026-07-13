-- LLM and image provider profiles are an enabled set, not a single default.
DROP INDEX IF EXISTS ux_user_api_keys_active_slot;

ALTER TABLE user_api_keys
    ALTER COLUMN model TYPE VARCHAR(2000);

CREATE INDEX IF NOT EXISTS ix_user_api_keys_active_slot
    ON user_api_keys (user_id, slot, created_at, id)
    WHERE active = TRUE;

-- Workflow execution still has a single configured default.
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_api_keys_active_workflow
    ON user_api_keys (user_id, slot)
    WHERE active = TRUE AND slot = 'workflow';
