CREATE TABLE accounts (
    id          uuid PRIMARY KEY,
    name        varchar(100) NOT NULL,
    currency    char(3)      NOT NULL DEFAULT 'EUR',
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE TABLE account_members (
    account_id  uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    joined_at   timestamptz NOT NULL,
    PRIMARY KEY (account_id, user_id)
);

CREATE TABLE invitations (
    id          uuid PRIMARY KEY,
    account_id  uuid         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    email       varchar(320) NOT NULL,
    token_hash  varchar(64)  NOT NULL,
    invited_by  uuid REFERENCES users (id) ON DELETE SET NULL,
    status      varchar(20)  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL,
    accepted_at timestamptz,
    CONSTRAINT invitations_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELED', 'EXPIRED'))
);
CREATE UNIQUE INDEX uq_invitations_token_hash ON invitations (token_hash);
CREATE UNIQUE INDEX uq_invitations_pending_per_account ON invitations (account_id) WHERE status = 'PENDING';
CREATE INDEX idx_invitations_account_id ON invitations (account_id);
CREATE INDEX idx_invitations_email ON invitations (email);

CREATE TABLE subscriptions (
    id                        uuid PRIMARY KEY,
    account_id                uuid        NOT NULL UNIQUE REFERENCES accounts (id) ON DELETE CASCADE,
    plan                      varchar(20) NOT NULL,
    status                    varchar(20) NOT NULL,
    current_period_end        timestamptz,
    provider                  varchar(20) NOT NULL DEFAULT 'NONE',
    provider_customer_id      varchar(255),
    provider_subscription_id  varchar(255),
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    CONSTRAINT subscriptions_plan_check CHECK (plan IN ('BETA')),
    CONSTRAINT subscriptions_status_check CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED')),
    CONSTRAINT subscriptions_provider_check CHECK (provider IN ('NONE'))
);

CREATE TABLE categories (
    id              uuid PRIMARY KEY,
    account_id      uuid         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    name            varchar(60)  NOT NULL,
    kind            varchar(10)  NOT NULL,
    color           varchar(9)   NOT NULL,
    icon            varchar(40)  NOT NULL,
    system_default  boolean      NOT NULL DEFAULT false,
    archived_at     timestamptz,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT categories_kind_check CHECK (kind IN ('INCOME', 'EXPENSE'))
);
CREATE UNIQUE INDEX uq_categories_active_name ON categories (account_id, kind, name) WHERE archived_at IS NULL;
