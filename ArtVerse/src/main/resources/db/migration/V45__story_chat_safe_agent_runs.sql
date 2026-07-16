ALTER TABLE manga_agent_runs
    ADD COLUMN IF NOT EXISTS run_type VARCHAR(32) NOT NULL DEFAULT 'MANGA_AGENT',
    ADD COLUMN IF NOT EXISTS route_key VARCHAR(64);

UPDATE manga_agent_runs
SET route_key = COALESCE(route_key, route)
WHERE route_key IS NULL;

ALTER TABLE manga_agent_runs
    ADD CONSTRAINT ck_manga_agent_runs_run_type
    CHECK (run_type IN ('MANGA_AGENT', 'STORY_CHAT'));

CREATE INDEX IF NOT EXISTS idx_manga_agent_runs_type_conversation_status
    ON manga_agent_runs(run_type, conversation_id, status, updated_at DESC);

ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS request_id UUID;

UPDATE chat_messages
SET request_id = gen_random_uuid()
WHERE request_id IS NULL;

ALTER TABLE chat_messages
    ALTER COLUMN request_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_messages_conversation_request_role
    ON chat_messages(conversation_id, request_id, role)
    WHERE conversation_id IS NOT NULL;

ALTER TABLE chapter_novel_revisions
    ADD COLUMN IF NOT EXISTS agent_run_artifact_id BIGINT REFERENCES manga_agent_run_artifacts(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_chapter_novel_revisions_agent_run_artifact_id
    ON chapter_novel_revisions(agent_run_artifact_id)
    WHERE agent_run_artifact_id IS NOT NULL;
