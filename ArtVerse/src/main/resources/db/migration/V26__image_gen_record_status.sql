ALTER TABLE image_gen_records
  ALTER COLUMN image_path DROP NOT NULL,
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
  ADD COLUMN failure_reason TEXT,
  ADD COLUMN completed_at TIMESTAMPTZ;

CREATE INDEX idx_image_gen_records_user_status_created
  ON image_gen_records(user_id, status, created_at DESC);
