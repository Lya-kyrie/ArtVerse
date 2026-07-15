-- Unify persisted AI conversations while preserving existing Manga Agent foreign keys.
ALTER TABLE manga_agent_conversations RENAME TO ai_conversations;
ALTER TABLE ai_conversations RENAME CONSTRAINT uk_manga_agent_conversations_uuid TO uk_ai_conversations_uuid;
ALTER INDEX idx_manga_agent_conversations_chapter_status RENAME TO idx_ai_conversations_chapter_status;
ALTER INDEX uk_one_active_conversation_per_user_chapter RENAME TO uk_one_active_manga_conversation_per_user_chapter;

ALTER TABLE ai_conversations
    ADD COLUMN conversation_type VARCHAR(32) NOT NULL DEFAULT 'MANGA_AGENT',
    ADD COLUMN title_source VARCHAR(16) NOT NULL DEFAULT 'FALLBACK',
    ADD COLUMN title_state VARCHAR(16) NOT NULL DEFAULT 'FINALIZED',
    ADD COLUMN last_activity_at TIMESTAMPTZ,
    ADD COLUMN title_generation_started_at TIMESTAMPTZ,
    ADD COLUMN legacy_import_key VARCHAR(160);

UPDATE ai_conversations
SET title = '新会话',
    title_source = 'DEFAULT',
    title_state = 'WAITING',
    last_activity_at = updated_at
WHERE title IN ('New chat', '默认对话', '默认会话', '新会话');

UPDATE ai_conversations
SET last_activity_at = updated_at
WHERE last_activity_at IS NULL;

ALTER TABLE ai_conversations
    ALTER COLUMN last_activity_at SET NOT NULL,
    ALTER COLUMN story_id DROP NOT NULL,
    ALTER COLUMN chapter_id DROP NOT NULL;

ALTER TABLE ai_conversations
    ADD CONSTRAINT ck_ai_conversations_type CHECK (conversation_type IN ('MANGA_AGENT', 'STORY_CHAT', 'IMAGE_GEN')),
    ADD CONSTRAINT ck_ai_conversations_title_source CHECK (title_source IN ('DEFAULT', 'AI', 'FALLBACK', 'USER')),
    ADD CONSTRAINT ck_ai_conversations_title_state CHECK (title_state IN ('WAITING', 'GENERATING', 'FINALIZED')),
    ADD CONSTRAINT ck_ai_conversations_scope CHECK (
        (conversation_type IN ('MANGA_AGENT', 'STORY_CHAT') AND story_id IS NOT NULL AND chapter_id IS NOT NULL)
        OR (conversation_type = 'IMAGE_GEN' AND story_id IS NULL AND chapter_id IS NULL)
    );

DROP INDEX uk_one_active_manga_conversation_per_user_chapter;
CREATE UNIQUE INDEX uk_ai_conversations_one_active_scoped
    ON ai_conversations(user_id, chapter_id, conversation_type)
    WHERE status = 'ACTIVE' AND conversation_type IN ('MANGA_AGENT', 'STORY_CHAT');
CREATE INDEX idx_ai_conversations_user_type_activity
    ON ai_conversations(user_id, conversation_type, status, last_activity_at DESC);
CREATE UNIQUE INDEX uk_ai_conversations_legacy_import
    ON ai_conversations(user_id, conversation_type, legacy_import_key)
    WHERE legacy_import_key IS NOT NULL;

ALTER TABLE chat_messages ADD COLUMN conversation_id BIGINT REFERENCES ai_conversations(id) ON DELETE CASCADE;
INSERT INTO ai_conversations (conversation_uuid, user_id, story_id, chapter_id, title, status, conversation_type, title_source, title_state, created_at, updated_at, last_activity_at)
SELECT gen_random_uuid(), s.user_id, c.story_id, c.id, '新会话', 'ACTIVE', 'STORY_CHAT', 'DEFAULT', 'WAITING', c.created_at, c.created_at, c.created_at
FROM chapters c JOIN stories s ON s.id = c.story_id
ON CONFLICT DO NOTHING;
UPDATE chat_messages m SET conversation_id = c.id
FROM ai_conversations c
WHERE c.conversation_type = 'STORY_CHAT' AND c.chapter_id = m.chapter_id AND m.conversation_id IS NULL;
CREATE INDEX idx_chat_messages_conversation_order ON chat_messages(conversation_id, created_at ASC);

ALTER TABLE image_gen_records ADD COLUMN conversation_id BIGINT REFERENCES ai_conversations(id) ON DELETE RESTRICT;
INSERT INTO ai_conversations (conversation_uuid, user_id, title, status, conversation_type, title_source, title_state, created_at, updated_at, last_activity_at)
SELECT gen_random_uuid(), r.user_id, '历史图片', 'ACTIVE', 'IMAGE_GEN', 'FALLBACK', 'FINALIZED', MIN(r.created_at), MAX(r.created_at), MAX(r.created_at)
FROM image_gen_records r GROUP BY r.user_id;
UPDATE image_gen_records r SET conversation_id = c.id
FROM ai_conversations c
WHERE c.user_id = r.user_id AND c.conversation_type = 'IMAGE_GEN' AND c.title = '历史图片' AND r.conversation_id IS NULL;
CREATE INDEX idx_image_gen_records_conversation_created ON image_gen_records(conversation_id, created_at DESC);
