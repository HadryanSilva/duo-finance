-- V5__create_password_reset_tokens.sql
-- Tokens de redefinição de senha — expiram em 1h e são de uso único

CREATE TABLE password_reset_tokens (
                                       id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                       user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                       token      VARCHAR(64)  NOT NULL UNIQUE,
                                       expires_at TIMESTAMP    NOT NULL,
                                       used       BOOLEAN      NOT NULL DEFAULT FALSE,
                                       created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prt_token   ON password_reset_tokens(token);
CREATE INDEX idx_prt_user_id ON password_reset_tokens(user_id);