-- Pockets live in the statement's own bank account: there is no pockets account to remember.
ALTER TABLE accounts DROP COLUMN pockets_bank_account_id;
