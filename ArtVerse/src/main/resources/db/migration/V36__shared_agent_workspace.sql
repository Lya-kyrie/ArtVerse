CREATE TABLE IF NOT EXISTS agent_workspace_items (
    namespace_key TEXT NOT NULL,
    item_key TEXT NOT NULL,
    value_json JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (namespace_key, item_key),
    CONSTRAINT ck_agent_workspace_version CHECK (version > 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_workspace_namespace
    ON agent_workspace_items(namespace_key, item_key);

ALTER TABLE user_embedding_configs
    ALTER COLUMN custom_headers TYPE TEXT USING custom_headers::text;
