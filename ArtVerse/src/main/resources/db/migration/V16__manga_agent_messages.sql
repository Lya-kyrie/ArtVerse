CREATE TABLE manga_agent_messages (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  request_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_manga_agent_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM'))
);

CREATE INDEX idx_manga_agent_messages_chapter_created
  ON manga_agent_messages(user_id, chapter_id, created_at);

CREATE UNIQUE INDEX uk_manga_agent_messages_request_role
  ON manga_agent_messages(user_id, request_id, role);
