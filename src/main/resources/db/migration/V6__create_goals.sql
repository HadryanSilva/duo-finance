CREATE TABLE goals (
                       id            UUID          NOT NULL DEFAULT gen_random_uuid(),
                       couple_id     UUID          NOT NULL,
                       category      VARCHAR(30)   NOT NULL,
                       monthly_limit NUMERIC(12,2) NOT NULL CHECK (monthly_limit > 0),
                       active        BOOLEAN       NOT NULL DEFAULT true,
                       created_at    TIMESTAMP     NOT NULL DEFAULT now(),
                       updated_at    TIMESTAMP     NOT NULL DEFAULT now(),

                       CONSTRAINT pk_goals PRIMARY KEY (id),
                       CONSTRAINT fk_goals_couple FOREIGN KEY (couple_id) REFERENCES couples(id) ON DELETE CASCADE,
                       CONSTRAINT uq_goals_couple_category UNIQUE (couple_id, category)
);

CREATE INDEX idx_goals_couple_active ON goals(couple_id, active);

COMMENT ON TABLE  goals               IS 'RF35 — Metas financeiras mensais por categoria';
COMMENT ON COLUMN goals.monthly_limit IS 'Limite máximo de gasto mensal para a categoria (EXPENSE only)';
COMMENT ON COLUMN goals.active        IS 'false = meta pausada sem ser excluída';