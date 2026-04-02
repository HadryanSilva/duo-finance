-- Suporte a importação OFX: armazena o FITID do banco como identificador externo.
-- Permite deduplicação precisa ao reimportar o mesmo extrato.
-- Nullable: transações criadas manualmente ou via XLSX não possuem external_id.

ALTER TABLE transactions
    ADD COLUMN external_id VARCHAR(100) DEFAULT NULL;

-- Unicidade por casal: o mesmo FITID não pode ser importado duas vezes no mesmo casal.
-- Partial index (WHERE external_id IS NOT NULL) evita conflito entre registros manuais.
CREATE UNIQUE INDEX idx_tx_couple_external_id
    ON transactions(couple_id, external_id)
    WHERE external_id IS NOT NULL;

COMMENT ON COLUMN transactions.external_id IS
    'ID externo do banco (ex: FITID do OFX). Usado para deduplicação em importações.';