DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM (
      SELECT btrim(username) AS normalized_username, COUNT(*) AS duplicate_count
      FROM users
      GROUP BY btrim(username)
      HAVING COUNT(*) > 1
    ) duplicates
  ) THEN
    RAISE EXCEPTION 'Cannot normalize usernames because trimmed duplicates exist';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM (
      SELECT lower(btrim(email)) AS normalized_email, COUNT(*) AS duplicate_count
      FROM users
      GROUP BY lower(btrim(email))
      HAVING COUNT(*) > 1
    ) duplicates
  ) THEN
    RAISE EXCEPTION 'Cannot normalize emails because case-insensitive duplicates exist';
  END IF;
END $$;

UPDATE users
SET username = btrim(username),
    email = lower(btrim(email))
WHERE username <> btrim(username)
   OR email <> lower(btrim(email));

ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_username_trimmed;
ALTER TABLE users
  ADD CONSTRAINT ck_users_username_trimmed
  CHECK (username = btrim(username));

ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_email_normalized;
ALTER TABLE users
  ADD CONSTRAINT ck_users_email_normalized
  CHECK (email = lower(btrim(email)));
