DROP INDEX uq_categories_active_name;
CREATE UNIQUE INDEX uq_categories_active_name ON categories (account_id, kind, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE bank_accounts (
    id                     uuid PRIMARY KEY,
    account_id             uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    name                   varchar(60) NOT NULL,
    type                   varchar(10) NOT NULL,
    initial_balance_cents  bigint      NOT NULL DEFAULT 0,
    holder_user_id         uuid REFERENCES users (id) ON DELETE SET NULL,
    archived_at            timestamptz,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    CONSTRAINT bank_accounts_type_check CHECK (type IN ('CHECKING', 'SAVINGS', 'CASH', 'OTHER'))
);
CREATE INDEX idx_bank_accounts_account_id ON bank_accounts (account_id);
CREATE INDEX idx_bank_accounts_holder_user_id ON bank_accounts (holder_user_id);

CREATE TABLE recurring_rules (
    id                          uuid PRIMARY KEY,
    account_id                  uuid         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    kind                        varchar(10)  NOT NULL,
    amount_cents                bigint       NOT NULL,
    description                 varchar(140),
    category_id                 uuid REFERENCES categories (id),
    bank_account_id             uuid REFERENCES bank_accounts (id),
    destination_bank_account_id uuid REFERENCES bank_accounts (id),
    frequency                   varchar(10)  NOT NULL,
    start_date                  date         NOT NULL,
    end_date                    date,
    next_run_date               date         NOT NULL,
    active                      boolean      NOT NULL DEFAULT true,
    created_by                  uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL,
    CONSTRAINT recurring_rules_kind_check CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    CONSTRAINT recurring_rules_amount_check CHECK (amount_cents > 0),
    CONSTRAINT recurring_rules_frequency_check CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT recurring_rules_end_date_check CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT recurring_rules_shape_check CHECK (
        (kind = 'TRANSFER' AND category_id IS NULL AND bank_account_id IS NOT NULL
            AND destination_bank_account_id IS NOT NULL AND bank_account_id <> destination_bank_account_id)
        OR (kind <> 'TRANSFER' AND category_id IS NOT NULL AND destination_bank_account_id IS NULL))
);
CREATE INDEX idx_recurring_rules_account_id ON recurring_rules (account_id);
CREATE INDEX idx_recurring_rules_created_by ON recurring_rules (created_by);
CREATE INDEX idx_recurring_rules_category_id ON recurring_rules (category_id);
CREATE INDEX idx_recurring_rules_bank_account_id ON recurring_rules (bank_account_id);
CREATE INDEX idx_recurring_rules_destination_bank_account_id ON recurring_rules (destination_bank_account_id);
CREATE INDEX idx_recurring_rules_active_next_run ON recurring_rules (active, next_run_date);

CREATE TABLE transactions (
    id                          uuid PRIMARY KEY,
    account_id                  uuid         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    kind                        varchar(10)  NOT NULL,
    amount_cents                bigint       NOT NULL,
    date                        date         NOT NULL,
    description                 varchar(140),
    category_id                 uuid REFERENCES categories (id),
    bank_account_id             uuid REFERENCES bank_accounts (id),
    destination_bank_account_id uuid REFERENCES bank_accounts (id),
    created_by                  uuid REFERENCES users (id) ON DELETE SET NULL,
    recurring_rule_id           uuid REFERENCES recurring_rules (id) ON DELETE SET NULL,
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL,
    CONSTRAINT transactions_kind_check CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    CONSTRAINT transactions_amount_check CHECK (amount_cents > 0),
    CONSTRAINT transactions_shape_check CHECK (
        (kind = 'TRANSFER' AND category_id IS NULL AND bank_account_id IS NOT NULL
            AND destination_bank_account_id IS NOT NULL AND bank_account_id <> destination_bank_account_id)
        OR (kind <> 'TRANSFER' AND category_id IS NOT NULL AND destination_bank_account_id IS NULL))
);
CREATE UNIQUE INDEX uq_transactions_rule_date ON transactions (recurring_rule_id, date)
    WHERE recurring_rule_id IS NOT NULL;
CREATE INDEX idx_transactions_account_id_date ON transactions (account_id, date DESC);
CREATE INDEX idx_transactions_bank_account_id ON transactions (bank_account_id);
CREATE INDEX idx_transactions_destination_bank_account_id ON transactions (destination_bank_account_id);
CREATE INDEX idx_transactions_category_id ON transactions (category_id);
CREATE INDEX idx_transactions_created_by ON transactions (created_by);
CREATE INDEX idx_transactions_recurring_rule_id ON transactions (recurring_rule_id);

CREATE TABLE budgets (
    id                  uuid PRIMARY KEY,
    account_id          uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    category_id         uuid        NOT NULL UNIQUE REFERENCES categories (id) ON DELETE CASCADE,
    monthly_limit_cents bigint      NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT budgets_limit_check CHECK (monthly_limit_cents > 0)
);
CREATE INDEX idx_budgets_account_id ON budgets (account_id);
