CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE user_embedding_configs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    display_name VARCHAR(120) NOT NULL DEFAULT '',
    base_url VARCHAR(500) NOT NULL DEFAULT '',
    api_key VARCHAR(1000) NOT NULL DEFAULT '',
    model VARCHAR(255) NOT NULL DEFAULT '',
    custom_headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    actual_dimension INTEGER,
    config_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    CONSTRAINT ck_embedding_config_status CHECK (status IN ('UNVERIFIED', 'VERIFIED', 'RETIRED'))
);
CREATE INDEX idx_embedding_configs_user ON user_embedding_configs(user_id, created_at DESC);

CREATE TABLE embedding_spaces (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    config_id BIGINT NOT NULL REFERENCES user_embedding_configs(id),
    config_version INTEGER NOT NULL,
    model_identifier VARCHAR(255) NOT NULL,
    dimensions INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_embedding_space_status CHECK (status IN ('READY', 'RETIRED')),
    CONSTRAINT uq_embedding_space_config_version UNIQUE(config_id, config_version)
);

CREATE TABLE story_embedding_spaces (
    story_id BIGINT PRIMARY KEY REFERENCES stories(id) ON DELETE CASCADE,
    embedding_space_id BIGINT NOT NULL REFERENCES embedding_spaces(id),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_units (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    character_profile_id BIGINT UNIQUE REFERENCES character_profiles(id) ON DELETE SET NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    structured_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    importance INTEGER NOT NULL DEFAULT 3,
    effective_from_chapter INTEGER,
    effective_to_chapter INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version INTEGER NOT NULL DEFAULT 1,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_type CHECK (type IN ('CHARACTER_CARD', 'CHARACTER_RELATION', 'WORLDVIEW', 'TIMELINE', 'FORESHADOWING')),
    CONSTRAINT ck_knowledge_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_importance CHECK (importance BETWEEN 1 AND 5),
    CONSTRAINT ck_knowledge_chapter_range CHECK (effective_to_chapter IS NULL OR effective_from_chapter IS NULL OR effective_to_chapter >= effective_from_chapter)
);
CREATE INDEX idx_knowledge_units_story_status ON knowledge_units(story_id, status, type);

CREATE TABLE knowledge_unit_revisions (
    id BIGSERIAL PRIMARY KEY,
    knowledge_unit_id BIGINT NOT NULL REFERENCES knowledge_units(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    summary TEXT NOT NULL,
    structured_data JSONB NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_revision UNIQUE(knowledge_unit_id, version)
);

CREATE TABLE knowledge_unit_chunks (
    id BIGSERIAL PRIMARY KEY,
    knowledge_unit_id BIGINT NOT NULL REFERENCES knowledge_units(id) ON DELETE CASCADE,
    embedding_space_id BIGINT NOT NULL REFERENCES embedding_spaces(id) ON DELETE CASCADE,
    source_version INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    embedding vector NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_chunk_version UNIQUE(knowledge_unit_id, embedding_space_id, source_version, chunk_index)
);
CREATE INDEX idx_knowledge_chunks_unit_space ON knowledge_unit_chunks(knowledge_unit_id, embedding_space_id);

CREATE TABLE knowledge_index_jobs (
    id BIGSERIAL PRIMARY KEY,
    knowledge_unit_id BIGINT NOT NULL REFERENCES knowledge_units(id) ON DELETE CASCADE,
    embedding_space_id BIGINT REFERENCES embedding_spaces(id) ON DELETE SET NULL,
    source_version INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'STALE'))
);
CREATE INDEX idx_knowledge_jobs_dispatch ON knowledge_index_jobs(status, next_run_at);

CREATE TABLE chapter_knowledge_snapshots (
    id BIGSERIAL PRIMARY KEY,
    chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    embedding_space_id BIGINT NOT NULL REFERENCES embedding_spaces(id),
    knowledge_items JSONB NOT NULL,
    context_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chapter_knowledge_snapshots_chapter ON chapter_knowledge_snapshots(chapter_id, created_at DESC);

-- Existing character profiles become canonical character-card knowledge units.
INSERT INTO knowledge_units (story_id, character_profile_id, type, title, body, summary, structured_data, importance, status, version, content_hash)
SELECT cp.story_id, cp.id, 'CHARACTER_CARD', cp.name, cp.description, cp.description, '{}'::jsonb, 5, 'ACTIVE', 1,
       encode(digest(cp.name || E'\\n' || coalesce(cp.description, ''), 'sha256'), 'hex')
FROM character_profiles cp
WHERE NOT EXISTS (SELECT 1 FROM knowledge_units ku WHERE ku.character_profile_id = cp.id);
