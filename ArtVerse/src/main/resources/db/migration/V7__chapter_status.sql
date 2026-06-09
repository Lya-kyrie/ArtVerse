-- V7__chapter_status.sql
-- Chapter 状态机字段 + CHECK 约束
-- 详见 docs/knowledge/modules/chapter/references/state-machine.md

ALTER TABLE chapters
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT';

-- 状态机 CHECK 约束（5 状态）
ALTER TABLE chapters
    ADD CONSTRAINT chk_chapter_status
    CHECK (status IN ('DRAFT', 'SCENES_READY', 'IMAGES_GENERATING', 'COMPLETED', 'FAILED'));

-- 索引：按状态查章节（如巡检 FAILED 章节）
CREATE INDEX idx_chapters_status ON chapters(status);

-- 索引：按 story + status 查（作品页列表）
CREATE INDEX idx_chapters_story_status ON chapters(story_id, status);
