-- Older imports duplicated the canonical chapter text into chat_messages as a
-- USER message. That row is source text, not conversation history.
DELETE FROM chat_messages m
USING chapters c
WHERE m.chapter_id = c.id
  AND UPPER(COALESCE(c.content_source, '')) = 'IMPORT'
  AND UPPER(m.role) = 'USER'
  AND NULLIF(BTRIM(c.novel_content), '') IS NOT NULL
  AND BTRIM(m.content) = BTRIM(c.novel_content);
