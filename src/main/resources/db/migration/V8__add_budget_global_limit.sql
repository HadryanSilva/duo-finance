-- V8__add_budget_global_limit.sql
-- Orçamento doméstico: limite global mensal por casal

ALTER TABLE couples
    ADD COLUMN global_monthly_limit NUMERIC(12,2) DEFAULT NULL;

COMMENT ON COLUMN couples.global_monthly_limit IS
    'Teto global de despesas mensais do casal. NULL = sem limite global definido.';