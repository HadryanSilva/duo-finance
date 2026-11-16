ALTER TABLE accounts ADD COLUMN salary_category_id uuid REFERENCES categories (id) ON DELETE SET NULL;

CREATE TABLE goals (
    id               uuid PRIMARY KEY,
    account_id       uuid         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    name             varchar(100) NOT NULL,
    target_cents     bigint       NOT NULL,
    target_date      date,
    monthly_percent  smallint     NOT NULL,
    completed_at     timestamptz,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT goals_target_check CHECK (target_cents > 0),
    CONSTRAINT goals_percent_check CHECK (monthly_percent BETWEEN 0 AND 100)
);
CREATE UNIQUE INDEX uq_goals_active_name ON goals (account_id, lower(name)) WHERE completed_at IS NULL;
CREATE INDEX idx_goals_account_id ON goals (account_id);

CREATE TABLE goal_movements (
    id            uuid PRIMARY KEY,
    goal_id       uuid         NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    account_id    uuid         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    type          varchar(16)  NOT NULL,
    amount_cents  bigint       NOT NULL,
    month         date,
    note          varchar(200),
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT goal_movements_type_check CHECK (type IN ('CONTRIBUTION', 'SKIP', 'DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT goal_movements_month_check CHECK ((type IN ('CONTRIBUTION', 'SKIP')) = (month IS NOT NULL)),
    CONSTRAINT goal_movements_amount_check CHECK (
        (type = 'SKIP' AND amount_cents = 0) OR (type <> 'SKIP' AND amount_cents > 0)),
    CONSTRAINT goal_movements_month_first_day_check CHECK (month IS NULL OR extract(day FROM month) = 1)
);
CREATE UNIQUE INDEX uq_goal_movements_month ON goal_movements (goal_id, month) WHERE month IS NOT NULL;
CREATE INDEX idx_goal_movements_goal_id ON goal_movements (goal_id);
CREATE INDEX idx_goal_movements_account_id ON goal_movements (account_id);
