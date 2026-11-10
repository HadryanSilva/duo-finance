CREATE TABLE import_batches (
    id              uuid PRIMARY KEY,
    account_id      uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    source          varchar(20) NOT NULL,
    bank_account_id uuid        NOT NULL REFERENCES bank_accounts (id),
    created_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    row_count       integer     NOT NULL,
    undone_at       timestamptz,
    created_at      timestamptz NOT NULL,
    CONSTRAINT import_batches_source_check CHECK (source IN ('REVOLUT')),
    CONSTRAINT import_batches_row_count_check CHECK (row_count >= 0)
);
CREATE INDEX idx_import_batches_account_created ON import_batches (account_id, created_at DESC);

ALTER TABLE transactions
    ADD COLUMN import_batch_id    uuid REFERENCES import_batches (id) ON DELETE SET NULL,
    ADD COLUMN import_fingerprint varchar(128);
CREATE UNIQUE INDEX uq_transactions_import_fingerprint
    ON transactions (account_id, import_fingerprint) WHERE import_fingerprint IS NOT NULL;
CREATE INDEX idx_transactions_import_batch_id ON transactions (import_batch_id);

CREATE TABLE category_memory (
    account_id      uuid         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    description_key varchar(200) NOT NULL,
    kind            varchar(10)  NOT NULL,
    category_id     uuid         NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    updated_at      timestamptz  NOT NULL,
    PRIMARY KEY (account_id, description_key, kind),
    CONSTRAINT category_memory_kind_check CHECK (kind IN ('INCOME', 'EXPENSE'))
);
