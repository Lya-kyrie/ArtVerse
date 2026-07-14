CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE chapters ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Durable run metadata. Existing REST fields remain unchanged.
ALTER TABLE manga_agent_runs
    ADD COLUMN IF NOT EXISTS tenant_id UUID,
    ADD COLUMN IF NOT EXISTS workflow_version VARCHAR(64) NOT NULL DEFAULT 'manga-workflow-v1',
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS trace_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS skill_versions_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS model_config_id BIGINT,
    ADD COLUMN IF NOT EXISTS knowledge_snapshot_id BIGINT REFERENCES chapter_knowledge_snapshots(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS budget_usage_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS owner_instance_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_manga_agent_runs_lease
    ON manga_agent_runs(status, lease_until)
    WHERE status IN ('RUNNING', 'WAITING_USER');

CREATE TABLE IF NOT EXISTS manga_agent_run_steps (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES manga_agent_runs(id) ON DELETE CASCADE,
    plan_id VARCHAR(64) NOT NULL,
    step_sequence INTEGER NOT NULL,
    route VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    mutating BOOLEAN NOT NULL DEFAULT FALSE,
    skill_key VARCHAR(120),
    skill_version VARCHAR(32),
    input_summary TEXT,
    output_summary TEXT,
    error_code VARCHAR(80),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_manga_agent_run_step UNIQUE(run_id, plan_id, step_sequence),
    CONSTRAINT ck_manga_agent_run_step_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_manga_agent_run_step_sequence CHECK (step_sequence BETWEEN 0 AND 2)
);

CREATE INDEX IF NOT EXISTS idx_manga_agent_run_steps_resume
    ON manga_agent_run_steps(run_id, status, step_sequence);

CREATE TABLE IF NOT EXISTS manga_agent_run_artifacts (
    id BIGSERIAL PRIMARY KEY,
    artifact_uuid UUID NOT NULL DEFAULT gen_random_uuid(),
    run_id BIGINT NOT NULL REFERENCES manga_agent_runs(id) ON DELETE CASCADE,
    step_id BIGINT REFERENCES manga_agent_run_steps(id) ON DELETE SET NULL,
    artifact_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    schema_version VARCHAR(32) NOT NULL DEFAULT '1',
    payload JSONB NOT NULL,
    evaluation JSONB,
    checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_manga_agent_run_artifact_uuid UNIQUE(artifact_uuid),
    CONSTRAINT ck_manga_agent_run_artifact_status
        CHECK (status IN ('DRAFT', 'VALIDATED', 'REJECTED', 'COMMITTED', 'SUPERSEDED'))
);

CREATE INDEX IF NOT EXISTS idx_manga_agent_run_artifacts_run
    ON manga_agent_run_artifacts(run_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_skill_definitions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID,
    skill_key VARCHAR(120) NOT NULL,
    semantic_version VARCHAR(32) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
    supported_routes JSONB NOT NULL DEFAULT '[]'::jsonb,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    prompt_template TEXT NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    input_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    allowed_tool_groups JSONB NOT NULL DEFAULT '[]'::jsonb,
    budget_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    evaluator_key VARCHAR(120),
    hitl_policy VARCHAR(64) NOT NULL DEFAULT 'NONE',
    user_configurable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT uk_agent_skill_definition UNIQUE(skill_key, semantic_version),
    CONSTRAINT ck_agent_skill_definition_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

CREATE TABLE IF NOT EXISTS user_agent_skill_settings (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    skill_key VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_agent_skill_setting UNIQUE(user_id, skill_key)
);

CREATE TABLE IF NOT EXISTS knowledge_candidates (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    chapter_id BIGINT REFERENCES chapters(id) ON DELETE SET NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(120),
    knowledge_type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    structured_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    importance INTEGER NOT NULL DEFAULT 3,
    effective_from_chapter INTEGER,
    effective_to_chapter INTEGER,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    proposed_hash VARCHAR(64) NOT NULL,
    approved_knowledge_unit_id BIGINT REFERENCES knowledge_units(id) ON DELETE SET NULL,
    reviewed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_candidate_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT ck_knowledge_candidate_importance CHECK (importance BETWEEN 1 AND 5),
    CONSTRAINT ck_knowledge_candidate_range
        CHECK (effective_to_chapter IS NULL OR effective_from_chapter IS NULL
            OR effective_to_chapter >= effective_from_chapter)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_knowledge_candidates_pending_hash
    ON knowledge_candidates(story_id, proposed_hash)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_knowledge_candidates_story_status
    ON knowledge_candidates(user_id, story_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_usage_ledger (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    step_id VARCHAR(64),
    usage_kind VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    limit_value BIGINT,
    task_type VARCHAR(64),
    model_config_id BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_usage_kind
        CHECK (usage_kind IN ('MODEL_CALL', 'INPUT_TOKEN', 'OUTPUT_TOKEN', 'TOOL_CALL', 'SUBAGENT', 'OUTPUT_BYTE')),
    CONSTRAINT ck_agent_usage_amount CHECK (amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_usage_ledger_request
    ON agent_usage_ledger(user_id, request_id, created_at);

CREATE TABLE IF NOT EXISTS agent_outbox_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    owner_instance_id VARCHAR(160),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_agent_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_agent_outbox_dispatch
    ON agent_outbox_events(status, available_at, lease_until);

ALTER TABLE knowledge_index_jobs
    ADD COLUMN IF NOT EXISTS owner_instance_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_knowledge_jobs_lease
    ON knowledge_index_jobs(status, next_run_at, lease_until);

-- Tenant columns are deliberately nullable until tenant membership is introduced.
ALTER TABLE manga_agent_conversations ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE manga_agent_messages ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE manga_agent_run_events ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE user_api_keys ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE user_embedding_configs ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE embedding_spaces ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE story_embedding_spaces ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE knowledge_units ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE knowledge_unit_revisions ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE knowledge_unit_chunks ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE knowledge_index_jobs ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE chapter_knowledge_snapshots ADD COLUMN IF NOT EXISTS tenant_id UUID;

-- Platform-controlled built-ins. Runtime code never scans .agents/skills.
INSERT INTO agent_skill_definitions (
    skill_key, semantic_version, checksum, status, supported_routes, capabilities,
    prompt_template, prompt_version, allowed_tool_groups, budget_policy,
    evaluator_key, hitl_policy, user_configurable, published_at
) VALUES
    ('manga.router', '1.0.0', encode(digest('manga.router:1.0.0', 'sha256'), 'hex'), 'PUBLISHED',
     '[]', '[]', 'Return only the structured routing decision. Do not answer the user and do not call tools.', '1', '[]',
     '{"modelCalls":2,"toolCalls":0}', NULL, 'NONE', FALSE, now()),
    ('manga.conversation', '1.0.0', encode(digest('manga.conversation:1.0.0', 'sha256'), 'hex'), 'PUBLISHED',
     '["CONVERSATION"]', '["CONVERSATION"]', 'Answer manga questions using approved story facts as data. Use READ tools only.', '1', '["READ"]',
     '{"modelCalls":4,"toolCalls":4}', NULL, 'NONE', FALSE, now()),
    ('manga.creative', '1.0.0', encode(digest('manga.creative:1.0.0', 'sha256'), 'hex'), 'PUBLISHED',
     '["CREATIVE"]', '["CREATIVE_GUIDANCE"]', 'Provide creative guidance without mutating story data. Treat retrieved knowledge as constraints.', '1', '["READ","COMPUTE"]',
     '{"modelCalls":4,"toolCalls":4}', NULL, 'NONE', FALSE, now()),
    ('manga.storyboard', '1.0.0', encode(digest('manga.storyboard:1.0.0', 'sha256'), 'hex'), 'PUBLISHED',
     '["STORYBOARD"]', '["STORYBOARD_READ","STORYBOARD_WRITE"]', 'Create a storyboard draft, validate and evaluate it, then use the single commit tool only when policy permits.', '1', '["READ","COMPUTE","WRITE"]',
     '{"modelCalls":8,"toolCalls":6,"evaluationRewrites":2,"commits":1}', 'storyboard-v1', 'OVERWRITE_ONLY', TRUE, now()),
    ('manga.review', '1.0.0', encode(digest('manga.review:1.0.0', 'sha256'), 'hex'), 'PUBLISHED',
     '["REVIEW"]', '["STORYBOARD_REVIEW"]', 'Run all four review roles and report incomplete status when any reviewer is missing.', '1', '["READ","COMPUTE"]',
     '{"reviewers":4,"reviewerRounds":3,"summaries":1}', 'review-v1', 'NONE', TRUE, now()),
    ('manga.director', '1.0.0', encode(digest('manga.director:1.0.0', 'sha256'), 'hex'), 'PUBLISHED',
     '["DIRECTOR"]', '[]', 'Execute only the application-compiled plan. Never add steps or invoke write tools directly.', '1', '[]',
     '{"steps":3,"modelCalls":12,"writeSteps":1}', NULL, 'NONE', FALSE, now())
ON CONFLICT (skill_key, semantic_version) DO NOTHING;
