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
