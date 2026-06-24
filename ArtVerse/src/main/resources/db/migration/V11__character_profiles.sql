-- V11: Individual character profiles with reference images and asset group association
CREATE TABLE character_profiles (
  id BIGSERIAL PRIMARY KEY,
  story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_character_profiles_story_id ON character_profiles(story_id);

CREATE TABLE asset_group_characters (
  asset_group_id BIGINT NOT NULL REFERENCES story_asset_groups(id) ON DELETE CASCADE,
  character_profile_id BIGINT NOT NULL REFERENCES character_profiles(id) ON DELETE CASCADE,
  PRIMARY KEY (asset_group_id, character_profile_id)
);