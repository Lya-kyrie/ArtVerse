-- V15: Persistent guard observability metrics and request events.
CREATE TABLE guard_metric_buckets (
  id BIGSERIAL PRIMARY KEY,
  bucket_type VARCHAR(16) NOT NULL,
  bucket_start TIMESTAMPTZ NOT NULL,
  action VARCHAR(64) NOT NULL,
  total BIGINT NOT NULL DEFAULT 0,
  leader_count BIGINT NOT NULL DEFAULT 0,
  follower_count BIGINT NOT NULL DEFAULT 0,
  success_hit_count BIGINT NOT NULL DEFAULT 0,
  failed_hit_count BIGINT NOT NULL DEFAULT 0,
  follower_rejected_count BIGINT NOT NULL DEFAULT 0,
  processing_rejected_count BIGINT NOT NULL DEFAULT 0,
  failed_count BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_guard_metric_bucket UNIQUE (bucket_type, bucket_start, action)
);

CREATE INDEX idx_guard_metric_buckets_lookup
  ON guard_metric_buckets(bucket_type, bucket_start DESC, action);

CREATE TABLE guard_events (
  id UUID PRIMARY KEY,
  event_time TIMESTAMPTZ NOT NULL,
  action VARCHAR(64) NOT NULL,
  scope VARCHAR(128) NOT NULL,
  decision VARCHAR(64) NOT NULL,
  result VARCHAR(64) NOT NULL,
  key_hash VARCHAR(32) NOT NULL,
  duration_ms BIGINT,
  summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  message VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_guard_events_time ON guard_events(event_time DESC);
CREATE INDEX idx_guard_events_action_time ON guard_events(action, event_time DESC);
