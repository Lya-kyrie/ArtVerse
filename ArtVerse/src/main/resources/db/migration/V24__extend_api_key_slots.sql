-- Add slot-based provider columns to user_api_keys.
-- slot is the new primary discriminator (llm / image / workflow);
-- provider becomes the display label / provider identifier.

-- 1. Add new columns (nullable during migration)
ALTER TABLE user_api_keys
    ADD COLUMN IF NOT EXISTS slot VARCHAR(30),
    ADD COLUMN IF NOT EXISTS label VARCHAR(100),
    ADD COLUMN IF NOT EXISTS base_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS model VARCHAR(100);

-- 2. Migrate existing rows: map legacy provider to the new slot scheme
UPDATE user_api_keys SET slot = 'llm' WHERE provider = 'deepseek' AND slot IS NULL;
UPDATE user_api_keys SET slot = 'image' WHERE provider = 'image2' AND slot IS NULL;
UPDATE user_api_keys SET slot = 'workflow' WHERE provider = 'coze' AND slot IS NULL;

-- 3. Populate label as a human-readable copy of provider for rows that have none
UPDATE user_api_keys SET label = provider WHERE label IS NULL;

-- 4. Make slot NOT NULL now that all rows have one
ALTER TABLE user_api_keys ALTER COLUMN slot SET NOT NULL;

-- 5. Drop old unique constraint and check constraint, add new ones
ALTER TABLE user_api_keys DROP CONSTRAINT IF EXISTS uq_user_api_keys;
ALTER TABLE user_api_keys DROP CONSTRAINT IF EXISTS ck_user_api_keys_provider;

ALTER TABLE user_api_keys ADD CONSTRAINT uq_user_api_keys UNIQUE (user_id, slot);
ALTER TABLE user_api_keys ADD CONSTRAINT ck_user_api_keys_slot CHECK (slot IN ('llm', 'image', 'workflow'));
