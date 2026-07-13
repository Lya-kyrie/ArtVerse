-- A user can keep several provider profiles in each capability slot.
ALTER TABLE user_api_keys
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE user_api_keys DROP CONSTRAINT IF EXISTS uq_user_api_keys;

-- Preserve the existing configuration as the active profile during migration.
UPDATE user_api_keys
SET active = TRUE
WHERE id IN (
    SELECT DISTINCT ON (user_id, slot) id
    FROM user_api_keys
    ORDER BY user_id, slot, created_at ASC, id ASC
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_api_keys_active_slot
    ON user_api_keys (user_id, slot)
    WHERE active = TRUE;
