-- provider_id é opcional para usuários com login local (email + senha)
ALTER TABLE users ALTER COLUMN provider_id DROP NOT NULL;