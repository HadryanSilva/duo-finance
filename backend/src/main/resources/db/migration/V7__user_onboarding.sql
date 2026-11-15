-- Onboarding completion moves from the browser to the server. Users who existed before this migration already
-- went through onboarding (or skipped it), so they count as onboarded.
ALTER TABLE users ADD COLUMN onboarded_at timestamptz;
UPDATE users SET onboarded_at = created_at;
