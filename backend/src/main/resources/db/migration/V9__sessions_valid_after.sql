-- Per-user session epoch: access tokens issued (iat, whole seconds) before this instant are rejected. Set whenever
-- every session of the user is revoked (password change or reset, pre-hijack Google link). Null: no epoch yet.
ALTER TABLE users ADD COLUMN sessions_valid_after timestamptz;
