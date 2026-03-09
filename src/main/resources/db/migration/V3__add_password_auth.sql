-- Adiciona suporte a login com email/senha
-- provider pode ser 'google' | 'local'
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);