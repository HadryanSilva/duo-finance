-- Movements carry the date they happened on; a month may now hold many contributions.
ALTER TABLE goal_movements ADD COLUMN occurred_on date;
UPDATE goal_movements
SET occurred_on = CASE WHEN type IN ('CONTRIBUTION', 'SKIP') THEN month ELSE (created_at AT TIME ZONE 'UTC')::date END;
ALTER TABLE goal_movements ALTER COLUMN occurred_on SET NOT NULL;

-- DEPOSIT folds into CONTRIBUTION, counted in the month it happened.
UPDATE goal_movements
SET type = 'CONTRIBUTION', month = date_trunc('month', occurred_on)::date
WHERE type = 'DEPOSIT';

ALTER TABLE goal_movements DROP CONSTRAINT goal_movements_type_check;
ALTER TABLE goal_movements ADD CONSTRAINT goal_movements_type_check CHECK (type IN ('CONTRIBUTION', 'SKIP', 'WITHDRAWAL'));
ALTER TABLE goal_movements DROP CONSTRAINT goal_movements_month_check;
ALTER TABLE goal_movements ADD CONSTRAINT goal_movements_month_check CHECK ((type IN ('CONTRIBUTION', 'SKIP')) = (month IS NOT NULL));

-- Only a SKIP is unique per month now.
DROP INDEX uq_goal_movements_month;
CREATE UNIQUE INDEX uq_goal_movements_skip_month ON goal_movements (goal_id, month) WHERE type = 'SKIP';

-- A movement may mirror an imported transaction.
ALTER TABLE goal_movements ADD COLUMN transaction_id uuid REFERENCES transactions (id) ON DELETE CASCADE;
CREATE UNIQUE INDEX uq_goal_movements_transaction ON goal_movements (transaction_id) WHERE transaction_id IS NOT NULL;

ALTER TABLE goals ADD COLUMN revolut_pocket varchar(100);
ALTER TABLE accounts ADD COLUMN pockets_bank_account_id uuid REFERENCES bank_accounts (id) ON DELETE SET NULL;
