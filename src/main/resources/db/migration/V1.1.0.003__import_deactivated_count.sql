ALTER TABLE sync_log ADD COLUMN deactivated_count INT NOT NULL DEFAULT 0;
ALTER TABLE sync_log ADD CONSTRAINT ck_sync_deactivated CHECK (deactivated_count >= 0);
