-- Additive writer extensions. Content owns source events; AI owns the other tables.
CREATE TABLE IF NOT EXISTS writing_assistant_settings (
 owner_user_id BIGINT NOT NULL,novel_id BIGINT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,instructions TEXT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(owner_user_id,novel_id)
);
ALTER TABLE chapter_ai_adoption ADD COLUMN novel_id BIGINT NULL, ADD INDEX idx_writer_adoption_book(owner_user_id,novel_id);
UPDATE chapter_ai_adoption a JOIN chapter c ON c.id=a.chapter_id SET a.novel_id=c.novel_id WHERE a.novel_id IS NULL;
CREATE TABLE IF NOT EXISTS writing_asset (
 id VARCHAR(36) PRIMARY KEY, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 kind VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL, body MEDIUMTEXT NOT NULL,
 metadata JSON NOT NULL, state VARCHAR(20) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 content_hash CHAR(64) NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 INDEX idx_writer_asset(owner_user_id,novel_id,kind,state,deleted)
);
CREATE TABLE IF NOT EXISTS writing_asset_revision (
 asset_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
 novel_id BIGINT NOT NULL, payload JSON NOT NULL, created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(asset_id,version)
);
CREATE TABLE IF NOT EXISTS writing_sync_config (
 owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 vectors BOOLEAN NOT NULL DEFAULT FALSE, summaries BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 1, updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(owner_user_id,novel_id)
);
CREATE TABLE IF NOT EXISTS writing_source_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 chapter_id BIGINT NULL, source_id VARCHAR(36) NULL, kind VARCHAR(24) NOT NULL,
 source_version BIGINT NOT NULL DEFAULT 0, state VARCHAR(20) NOT NULL DEFAULT 'NEW',
 lease_token VARCHAR(36) NULL, lease_until DATETIME(3) NULL,
 attempts INT NOT NULL DEFAULT 0, error VARCHAR(500) NULL,
 processed_version BIGINT NULL,processed_hash CHAR(64) NULL,
 next_attempt_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
 created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)), updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
 INDEX idx_writer_source_queue(state,next_attempt_at,id), INDEX idx_writer_source_owner(owner_user_id,novel_id,id)
);
ALTER TABLE writing_source_event ALTER COLUMN next_attempt_at SET DEFAULT (UTC_TIMESTAMP(3)), ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP(3)), ALTER COLUMN updated_at SET DEFAULT (UTC_TIMESTAMP(3));
-- Compatibility ALTER is applied conditionally by ApplyWritingMigration; fresh fixtures already have these fields.
UPDATE writing_source_event SET next_attempt_at=UTC_TIMESTAMP(3),created_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE state='NEW' AND next_attempt_at>DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE);
CREATE TABLE IF NOT EXISTS writing_chapter_recap (
 owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL, chapter_id BIGINT NOT NULL,
 chapter_no INT NOT NULL, source_version BIGINT NOT NULL, source_hash CHAR(64) NOT NULL,
 model_identity VARCHAR(255) NOT NULL, summary JSON NOT NULL, state VARCHAR(20) NOT NULL,
 read_chars INT NOT NULL, total_chars INT NOT NULL, updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(owner_user_id,chapter_id), INDEX idx_writer_recap_book(owner_user_id,novel_id,chapter_no,state)
);
CREATE TABLE IF NOT EXISTS writing_index_receipt (
 owner_user_id BIGINT NOT NULL,novel_id BIGINT NOT NULL,chapter_id BIGINT NOT NULL,
 source_version BIGINT NOT NULL,source_hash CHAR(64) NOT NULL,chapter_no INT NOT NULL,
 title VARCHAR(200) NOT NULL,model_identity VARCHAR(255) NOT NULL,chunks INT NOT NULL,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(owner_user_id,chapter_id),INDEX idx_writer_index_book(owner_user_id,novel_id)
);
CREATE TABLE IF NOT EXISTS writing_cumulative_recap (
 owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL, through_chapter_no INT NOT NULL,
 manifest_hash CHAR(64) NOT NULL, source_keys JSON NOT NULL, summary JSON NOT NULL,
 model_identity VARCHAR(255) NOT NULL, state VARCHAR(20) NOT NULL, updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(owner_user_id,novel_id,through_chapter_no)
);
CREATE TABLE IF NOT EXISTS writing_derivation_job (
 id VARCHAR(36) PRIMARY KEY, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 asset_id VARCHAR(36) NULL, kind VARCHAR(24) NOT NULL, request JSON NOT NULL,
 state VARCHAR(20) NOT NULL, lease_token VARCHAR(36) NULL, lease_until DATETIME(3) NULL,
 result JSON NULL, error VARCHAR(500) NULL, usage_tokens INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 INDEX idx_writer_derive_queue(state,created_at), INDEX idx_writer_derive_owner(owner_user_id,novel_id,created_at)
);
CREATE TABLE IF NOT EXISTS writing_draft_revision (
 id VARCHAR(36) PRIMARY KEY, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, branch_group VARCHAR(36) NOT NULL, parent_id VARCHAR(36) NULL,
 name VARCHAR(100) NOT NULL, body MEDIUMTEXT NOT NULL, check_report JSON NOT NULL,
 source_hash CHAR(64) NOT NULL, draft_attempt_id VARCHAR(36) NOT NULL, created_at DATETIME(3) NOT NULL,
 INDEX idx_writer_revision_task(owner_user_id,task_id,created_at), INDEX idx_writer_branch(owner_user_id,branch_group,created_at)
);
CREATE TABLE IF NOT EXISTS writing_task_dependency (
 task_id VARCHAR(36) NOT NULL, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 source_kind VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL, source_version VARCHAR(64) NOT NULL,
 PRIMARY KEY(task_id,source_kind,source_id), INDEX idx_writer_dependency(owner_user_id,novel_id,source_kind,source_id)
);
CREATE TABLE IF NOT EXISTS writing_mcp_connection (
 id VARCHAR(36) PRIMARY KEY, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, endpoint VARCHAR(1000) NOT NULL, encrypted_token TEXT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, catalog JSON NOT NULL, grants JSON NOT NULL,
 catalog_hash CHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 INDEX idx_writer_mcp_owner(owner_user_id,novel_id)
);
CREATE TABLE IF NOT EXISTS writing_mcp_audit (
 id VARCHAR(36) PRIMARY KEY, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 task_id VARCHAR(36) NULL, connection_id VARCHAR(36) NOT NULL, tool_name VARCHAR(200) NOT NULL,
 input_hash CHAR(64) NOT NULL, input_chars INT NOT NULL, output_hash CHAR(64) NULL,
 output_chars INT NOT NULL DEFAULT 0, duration_ms BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
 error VARCHAR(500) NULL, created_at DATETIME(3) NOT NULL,
 INDEX idx_writer_mcp_audit(owner_user_id,novel_id,created_at)
);
CREATE TABLE IF NOT EXISTS writing_delete_ledger (
 id VARCHAR(64) PRIMARY KEY, owner_user_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 source_kind VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL,
 state VARCHAR(20) NOT NULL, result JSON NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 INDEX idx_writer_delete_owner(owner_user_id,novel_id,created_at)
);
