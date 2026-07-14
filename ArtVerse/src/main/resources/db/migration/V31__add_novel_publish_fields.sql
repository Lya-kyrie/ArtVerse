-- Existing publish fields represent the manga reader. Add an independent
-- publication track for the novel representation of the same story.
ALTER TABLE stories ADD COLUMN IF NOT EXISTS novel_is_published BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE stories ADD COLUMN IF NOT EXISTS novel_published_at TIMESTAMPTZ;

ALTER TABLE chapters ADD COLUMN IF NOT EXISTS novel_is_published BOOLEAN NOT NULL DEFAULT false;

