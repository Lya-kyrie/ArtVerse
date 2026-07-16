ALTER TABLE chat_messages
  ADD COLUMN completion_status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE';

UPDATE chat_messages
SET completion_status = 'PARTIAL'
WHERE content LIKE '%[已中止]%'
   OR content LIKE '%[宸蹭腑姝%';

ALTER TABLE chat_messages
  ADD CONSTRAINT ck_chat_messages_completion_status
  CHECK (completion_status IN ('COMPLETE', 'PARTIAL'));

CREATE TABLE novel_content_proposals (
    id UUID PRIMARY KEY,
    chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    conversation_id BIGINT NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    through_message_id BIGINT NOT NULL REFERENCES chat_messages(id) ON DELETE RESTRICT,
    base_version BIGINT NOT NULL,
    generated_content TEXT NOT NULL,
    generated_content_hash VARCHAR(64) NOT NULL,
    draft_content TEXT NOT NULL,
    draft_content_hash VARCHAR(64) NOT NULL,
    provider_config_id BIGINT REFERENCES user_api_keys(id) ON DELETE SET NULL,
    model VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at TIMESTAMPTZ,
    CONSTRAINT ck_novel_content_proposals_status CHECK (status IN ('DRAFT', 'COMMITTED'))
);

CREATE INDEX idx_novel_content_proposals_chapter_status
  ON novel_content_proposals(chapter_id, status, created_at DESC);

CREATE INDEX idx_novel_content_proposals_conversation_message
  ON novel_content_proposals(conversation_id, through_message_id);

ALTER TABLE chapter_novel_revisions
  ADD COLUMN proposal_id UUID REFERENCES novel_content_proposals(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_chapter_novel_revisions_proposal_id
  ON chapter_novel_revisions(proposal_id)
  WHERE proposal_id IS NOT NULL;
