-- V1__create_couples_and_users.sql

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Couples ───────────────────────────────────────────────────────────────────
CREATE TABLE couples (
                         id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                         name                VARCHAR(100) NOT NULL,
                         invite_token        VARCHAR(64),
                         invite_expires_at   TIMESTAMP,
                         created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
                         updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ── Users ─────────────────────────────────────────────────────────────────────
CREATE TABLE users (
                       id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                       couple_id   UUID         REFERENCES couples(id) ON DELETE SET NULL,
                       first_name  VARCHAR(100) NOT NULL,
                       last_name   VARCHAR(100) NOT NULL,
                       email       VARCHAR(255) NOT NULL UNIQUE,
                       provider    VARCHAR(30)  NOT NULL,       -- 'google' | 'github'
                       provider_id VARCHAR(100) NOT NULL,
                       avatar_url  VARCHAR(500),
                       created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
                       updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email     ON users(email);
CREATE INDEX idx_users_couple_id ON users(couple_id);

-- ── Refresh Tokens ────────────────────────────────────────────────────────────
-- Armazenamos os refresh tokens para possibilitar revogação (logout)
CREATE TABLE refresh_tokens (
                                id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                token      VARCHAR(512) NOT NULL UNIQUE,
                                expires_at TIMESTAMP    NOT NULL,
                                revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
                                created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_token   ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);