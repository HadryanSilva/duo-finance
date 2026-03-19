-- V10__create_notifications.sql
-- RF48 + RF49: Central de notificações in-app com preferências por usuário

CREATE TABLE notifications (
                               id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                               user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               couple_id   UUID        NOT NULL REFERENCES couples(id) ON DELETE CASCADE,
                               type        VARCHAR(40) NOT NULL,
                               title       VARCHAR(120) NOT NULL,
                               message     VARCHAR(500) NOT NULL,
                               read        BOOLEAN     NOT NULL DEFAULT false,
                               created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_read    ON notifications(user_id, read);
CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);

-- Preferências: um registro por usuário (insert-or-default)
CREATE TABLE notification_settings (
                                       user_id     UUID    PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                                       enabled     BOOLEAN NOT NULL DEFAULT true,
                                       updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  notifications          IS 'RF48 — Notificações in-app por usuário';
COMMENT ON COLUMN notifications.type     IS 'GOAL_WARNING | GOAL_EXCEEDED | BUDGET_EXCEEDED | PARTNER_JOINED';
COMMENT ON TABLE  notification_settings  IS 'RF49 — Preferências de notificação por usuário';
COMMENT ON COLUMN notification_settings.enabled IS 'false = usuário desativou todas as notificações';