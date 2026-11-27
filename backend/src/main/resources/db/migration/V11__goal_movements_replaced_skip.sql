-- A contribution that superseded its month's skip says so, so removing it can put the skip back.
ALTER TABLE goal_movements ADD COLUMN replaced_skip boolean NOT NULL DEFAULT false;
