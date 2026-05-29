CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) NOT NULL,
  email VARCHAR(200) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_users_username UNIQUE(username),
  CONSTRAINT uq_users_email UNIQUE(email)
);

CREATE TABLE user_api_keys (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider VARCHAR(30) NOT NULL,
  api_key VARCHAR(500) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_user_api_keys UNIQUE(user_id, provider),
  CONSTRAINT ck_user_api_keys_provider CHECK (provider IN ('deepseek', 'image2'))
);
CREATE INDEX idx_user_api_keys_user_id ON user_api_keys(user_id);

ALTER TABLE stories ADD COLUMN user_id BIGINT REFERENCES users(id);
CREATE INDEX idx_stories_user_id ON stories(user_id);
