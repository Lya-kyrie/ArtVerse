ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS skill_versions_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE novel_content_proposals
    ADD COLUMN IF NOT EXISTS skill_versions_json JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Runtime business skills are now loaded from classpath business-agent-skills resources.
-- Retain the old manifests for audit history only and retire the built-in runtime rows.
UPDATE agent_skill_definitions
SET status = 'RETIRED'
WHERE skill_key IN (
    'manga.router',
    'manga.conversation',
    'manga.creative',
    'manga.storyboard',
    'manga.review',
    'manga.director'
) AND status = 'PUBLISHED';
