-- V9__create_budgets.sql
-- Orçamento doméstico independente das metas financeiras
-- Base: renda conjunta do casal (monthly_income) com distribuição por percentual

-- Renda mensal fixa do casal na tabela couples
ALTER TABLE couples
    ADD COLUMN monthly_income NUMERIC(12,2) DEFAULT NULL;

-- Remove o campo anterior (limite global das metas) se existir
ALTER TABLE couples
DROP COLUMN IF EXISTS global_monthly_limit;

-- Tabela de orçamento: percentual por categoria
CREATE TABLE budgets (
                         id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                         couple_id   UUID          NOT NULL REFERENCES couples(id) ON DELETE CASCADE,
                         category    VARCHAR(30)   NOT NULL,
                         percentage  NUMERIC(5,2)  NOT NULL CHECK (percentage > 0 AND percentage <= 100),
                         created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
                         updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),

                         CONSTRAINT uq_budgets_couple_category UNIQUE (couple_id, category)
);

CREATE INDEX idx_budgets_couple ON budgets(couple_id);

COMMENT ON TABLE  budgets            IS 'Orçamento doméstico: distribuição percentual da renda por categoria';
COMMENT ON COLUMN budgets.percentage IS 'Percentual da renda mensal alocado para esta categoria';
COMMENT ON COLUMN couples.monthly_income IS 'Renda conjunta mensal do casal — base de cálculo do orçamento';