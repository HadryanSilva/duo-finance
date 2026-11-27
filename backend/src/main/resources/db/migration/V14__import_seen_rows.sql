-- Statement rows an import saw but that changed nothing: a pocket withdrawal from an empty goal records no movement.
-- Keeping the row's fingerprint stops a later overlapping import from applying it against money saved since.
CREATE TABLE import_seen_rows (
    account_id      uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    fingerprint     text NOT NULL,
    import_batch_id uuid NOT NULL REFERENCES import_batches (id) ON DELETE CASCADE,
    PRIMARY KEY (account_id, fingerprint)
);
CREATE INDEX import_seen_rows_batch_idx ON import_seen_rows (import_batch_id);
