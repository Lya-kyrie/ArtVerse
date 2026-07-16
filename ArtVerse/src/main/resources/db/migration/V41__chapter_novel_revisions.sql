CREATE TABLE chapter_novel_revisions (
    id BIGSERIAL PRIMARY KEY,
    chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    revision_number INT NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    created_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chapter_novel_revision UNIQUE (chapter_id, revision_number),
    CONSTRAINT ck_chapter_novel_revision_source CHECK (source IN ('MANUAL', 'AI', 'RESTORE', 'LEGACY_IMPORT', 'GENERATED'))
);

CREATE INDEX idx_chapter_novel_revisions_chapter_id ON chapter_novel_revisions(chapter_id, revision_number DESC);

INSERT INTO chapter_novel_revisions (chapter_id, revision_number, content, content_hash, source, created_by_user_id)
SELECT c.id, 1, c.novel_content, encode(digest(c.novel_content, 'sha256'), 'hex'), 'LEGACY_IMPORT', s.user_id
FROM chapters c
JOIN stories s ON s.id = c.story_id
WHERE NULLIF(BTRIM(c.novel_content), '') IS NOT NULL;
