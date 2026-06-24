-- V14: Image generation records for user chat-style generation
CREATE TABLE image_gen_records (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  prompt TEXT NOT NULL,
  image_path VARCHAR(500) NOT NULL,
  model VARCHAR(64) NOT NULL DEFAULT '',
  size VARCHAR(32) NOT NULL DEFAULT '1024x1024',
  is_deleted BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_image_gen_records_user_id ON image_gen_records(user_id);
CREATE INDEX idx_image_gen_records_user_created ON image_gen_records(user_id, created_at DESC);
