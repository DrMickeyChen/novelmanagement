-- Apply once BEFORE restarting novel-content / novel-ai with this release.
-- Existing chapter save clients must send expectedVersion after deployment.
ALTER TABLE chapter ADD COLUMN content_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE writing_gate (owner_user_id BIGINT PRIMARY KEY) ENGINE=InnoDB;

CREATE TABLE writing_task (
 id VARCHAR(36) PRIMARY KEY, owner_user_id BIGINT NOT NULL,
 chapter_id BIGINT NOT NULL, novel_id BIGINT NOT NULL,
 status VARCHAR(32) NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 payload MEDIUMTEXT NOT NULL, last_event_seq BIGINT NOT NULL DEFAULT 0,
 lease_owner VARCHAR(36), lease_epoch BIGINT NOT NULL DEFAULT 0, lease_until DATETIME(3),
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 INDEX(owner_user_id, updated_at), INDEX(status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE writing_attempt (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL,
 phase VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
 output_text MEDIUMTEXT NOT NULL, usage_json TEXT,
 created_at DATETIME(3) NOT NULL, finished_at DATETIME(3), INDEX(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE writing_event (
 task_id VARCHAR(36) NOT NULL, seq BIGINT NOT NULL, event_type VARCHAR(32) NOT NULL,
 attempt_id VARCHAR(36), payload MEDIUMTEXT NOT NULL, created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(task_id,seq), INDEX(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE writing_command (
 owner_user_id BIGINT NOT NULL, scope_id VARCHAR(36) NOT NULL,
 operation VARCHAR(16) NOT NULL, command_key VARCHAR(100) NOT NULL,
 request_hash CHAR(64) NOT NULL, result_id VARCHAR(36), created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(owner_user_id,scope_id,operation,command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chapter_ai_adoption (
 id VARCHAR(36) PRIMARY KEY, owner_user_id BIGINT NOT NULL, chapter_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, operation VARCHAR(8) NOT NULL, command_key VARCHAR(100) NOT NULL,
 request_hash CHAR(64) NOT NULL, before_version BIGINT NOT NULL, after_version BIGINT NOT NULL,
 before_content MEDIUMTEXT NOT NULL, result_content MEDIUMTEXT NOT NULL, title VARCHAR(200) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY task_operation(task_id,operation),
 UNIQUE KEY command_identity(owner_user_id,chapter_id,operation,command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
