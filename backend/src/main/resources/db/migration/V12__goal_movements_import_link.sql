-- Pocket moves stay in the statement's own bank account: an imported goal movement is no longer tied to a transaction.
-- It keeps the statement row's fingerprint (so a later import knows it) and the batch that made it (so undo finds it).
ALTER TABLE goal_movements DROP COLUMN transaction_id;
ALTER TABLE goal_movements ADD COLUMN import_fingerprint text;
ALTER TABLE goal_movements ADD COLUMN import_batch_id uuid REFERENCES import_batches (id) ON DELETE CASCADE;
ALTER TABLE goal_movements ADD CONSTRAINT goal_movements_import_link_check
    CHECK ((import_fingerprint IS NULL) = (import_batch_id IS NULL));
CREATE UNIQUE INDEX goal_movements_import_fingerprint_uq ON goal_movements (account_id, import_fingerprint)
    WHERE import_fingerprint IS NOT NULL;
CREATE INDEX goal_movements_import_batch_idx ON goal_movements (import_batch_id);
