CREATE UNIQUE INDEX IF NOT EXISTS uk_one_active_conversation_per_user_chapter
    ON manga_agent_conversations(user_id, chapter_id)
    WHERE status = 'ACTIVE';
