-- Author-owned records; no existing manuscript data is changed.
CREATE TABLE IF NOT EXISTS writing_story_entry (
 id VARCHAR(36) PRIMARY KEY,
 owner_user_id BIGINT NOT NULL,
 novel_id BIGINT NOT NULL,
 kind VARCHAR(20) NOT NULL,
 name VARCHAR(100) NOT NULL,
 aliases JSON NOT NULL,
 body TEXT NOT NULL,
 meaning VARCHAR(20) NOT NULL,
 state VARCHAR(20) NOT NULL,
 effective_from INT NOT NULL DEFAULT 1,
 effective_to INT NULL,
 core BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 1,
 source_chapter_id BIGINT NULL,
 source_version BIGINT NULL,
 source_quote TEXT NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 INDEX idx_story_owner_novel(owner_user_id,novel_id,state,effective_from)
);
CREATE TABLE IF NOT EXISTS writing_story_revision (
 entry_id VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL,
 owner_user_id BIGINT NOT NULL,
 novel_id BIGINT NOT NULL,
 payload JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(entry_id,version)
);
CREATE TABLE IF NOT EXISTS writing_private_index_job (
 id VARCHAR(36) PRIMARY KEY,
 owner_user_id BIGINT NOT NULL,
 chapter_id BIGINT NOT NULL,
 node_id BIGINT NOT NULL,
 request JSON NOT NULL,
 state VARCHAR(20) NOT NULL,
 result JSON NULL,
 error VARCHAR(500) NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 INDEX idx_writer_index_queue(state,created_at),
 INDEX idx_writer_index_owner(owner_user_id,created_at)
);
