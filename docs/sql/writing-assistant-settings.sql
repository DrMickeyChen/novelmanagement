-- Incremental upgrade for existing installations; also included in writing-agent-extensions.sql for fresh setups.
CREATE TABLE IF NOT EXISTS writing_assistant_settings (
 owner_user_id BIGINT NOT NULL,novel_id BIGINT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,instructions TEXT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(owner_user_id,novel_id)
);
