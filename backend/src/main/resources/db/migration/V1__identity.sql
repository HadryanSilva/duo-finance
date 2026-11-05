CREATE TABLE users (
    id                  uuid PRIMARY KEY,
    email               varchar(320) NOT NULL,
    display_name        varchar(100) NOT NULL,
    locale              varchar(10)  NOT NULL,
    email_verified_at   timestamptz,
    role                varchar(20)  NOT NULL DEFAULT 'USER',
    failed_login_count  int          NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    terms_version       varchar(50)  NOT NULL,
    terms_accepted_at   timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE user_identities (
    id                uuid PRIMARY KEY,
    user_id           uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider          varchar(20)  NOT NULL,
    provider_subject  varchar(255) NOT NULL,
    password_hash     varchar(255),
    created_at        timestamptz  NOT NULL,
    CONSTRAINT user_identities_provider_check CHECK (provider IN ('LOCAL', 'GOOGLE')),
    CONSTRAINT user_identities_provider_subject_key UNIQUE (provider, provider_subject)
);
CREATE INDEX idx_user_identities_user_id ON user_identities (user_id);

CREATE TABLE refresh_tokens (
    id             uuid PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id      uuid        NOT NULL,
    token_hash     varchar(64) NOT NULL,
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    replaced_by_id uuid REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX uq_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

CREATE TABLE user_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        varchar(30) NOT NULL,
    token_hash  varchar(64) NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL,
    CONSTRAINT user_tokens_type_check CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);
CREATE UNIQUE INDEX uq_user_tokens_token_hash ON user_tokens (token_hash);
CREATE INDEX idx_user_tokens_user_id ON user_tokens (user_id);
