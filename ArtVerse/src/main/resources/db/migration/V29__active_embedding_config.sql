ALTER TABLE user_embedding_configs
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE user_embedding_configs
SET active = FALSE;

UPDATE user_embedding_configs
SET active = TRUE
WHERE id IN (
    SELECT DISTINCT ON (user_id) id
    FROM user_embedding_configs
    WHERE status = 'VERIFIED'
    ORDER BY user_id, verified_at DESC NULLS LAST, created_at DESC, id DESC
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_embedding_configs_active
    ON user_embedding_configs (user_id)
    WHERE active = TRUE;
