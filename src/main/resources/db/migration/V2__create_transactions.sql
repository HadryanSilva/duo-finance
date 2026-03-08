-- V2__create_transactions.sql

CREATE TABLE transactions (
                              id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                              couple_id             UUID          NOT NULL REFERENCES couples(id) ON DELETE CASCADE,
                              user_id               UUID          NOT NULL REFERENCES users(id),
                              category              VARCHAR(30)   NOT NULL,
                              type                  VARCHAR(10)   NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
                              amount                NUMERIC(12,2) NOT NULL CHECK (amount > 0),
                              description           VARCHAR(255),
                              date                  DATE          NOT NULL,
                              is_recurring          BOOLEAN       NOT NULL DEFAULT FALSE,
                              recurrence_rule       VARCHAR(20)   CHECK (recurrence_rule IN ('DAILY','WEEKLY','MONTHLY','YEARLY')),
                              recurrence_end_date   DATE,
                              parent_transaction_id UUID          REFERENCES transactions(id),
                              created_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
                              updated_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
                              deleted_at            TIMESTAMP
);

CREATE INDEX idx_tx_couple_date     ON transactions(couple_id, date);
CREATE INDEX idx_tx_couple_category ON transactions(couple_id, category);
CREATE INDEX idx_tx_user            ON transactions(user_id);