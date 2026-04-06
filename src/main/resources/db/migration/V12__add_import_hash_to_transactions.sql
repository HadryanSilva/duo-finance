-- Hash imutável gerado no momento da importação a partir dos dados brutos do arquivo.
-- Garante deduplicação mesmo após edição da descrição pelo usuário.
-- Nullable: transações criadas manualmente não possuem import_hash.

ALTER TABLE transactions
    ADD COLUMN import_hash VARCHAR(64) DEFAULT NULL;

-- Unicidade por casal: o mesmo hash não pode existir duas vezes no mesmo casal.
-- Partial index (WHERE import_hash IS NOT NULL) evita conflito entre registros manuais.
CREATE UNIQUE INDEX idx_tx_couple_import_hash
    ON transactions(couple_id, import_hash)
    WHERE import_hash IS NOT NULL;

COMMENT ON COLUMN transactions.import_hash IS
    'SHA-256 dos dados brutos da importação (coupleId+fitId para OFX, coupleId+date+amount+desc para XLSX). Imutável após criação.';