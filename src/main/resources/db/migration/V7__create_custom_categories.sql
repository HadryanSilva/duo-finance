-- V7__create_custom_categories.sql
-- RF30/RF31 — Categorias personalizadas por casal

CREATE TABLE custom_categories (
                                   id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                   couple_id  UUID         NOT NULL REFERENCES couples(id) ON DELETE CASCADE,
                                   name       VARCHAR(60)  NOT NULL,
                                   type       VARCHAR(10)  NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
                                   icon       VARCHAR(60)  NOT NULL DEFAULT 'pi pi-tag',
                                   active     BOOLEAN      NOT NULL DEFAULT TRUE,
                                   created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
                                   updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),

                                   CONSTRAINT uq_custom_categories_couple_name UNIQUE (couple_id, name)
);

CREATE INDEX idx_custom_cat_couple ON custom_categories(couple_id, active);

-- Adiciona FK opcional em transactions para categoria customizada
ALTER TABLE transactions
    ADD COLUMN custom_category_id UUID REFERENCES custom_categories(id) ON DELETE RESTRICT;

COMMENT ON COLUMN transactions.custom_category_id IS
    'Preenchido quando a transação usa uma categoria personalizada. '
    'Mutuamente exclusivo com category — exatamente um deve estar preenchido.';