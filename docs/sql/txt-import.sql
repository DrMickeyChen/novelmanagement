-- Execute on the content database before deploying the TXT import feature.
-- Additive schema only: existing novel/chapter columns are unchanged.
CREATE TABLE IF NOT EXISTS novel_source (
  novel_id BIGINT NOT NULL,
  source_type VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL',
  original_author VARCHAR(100) NOT NULL,
  imported_by BIGINT NOT NULL,
  file_hash CHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (novel_id),
  UNIQUE KEY uk_source_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='External book attribution and import identity';

CREATE TABLE IF NOT EXISTS novel_import_task (
  id CHAR(36) NOT NULL,
  operator_id BIGINT NOT NULL,
  filename VARCHAR(255) NOT NULL,
  file_hash CHAR(64) NOT NULL,
  encoding VARCHAR(20) NOT NULL,
  parse_mode VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  preview_json LONGTEXT NOT NULL,
  options_json TEXT NULL,
  total_chapters INT NOT NULL DEFAULT 0,
  imported_chapters INT NOT NULL DEFAULT 0,
  novel_id BIGINT NULL,
  error_message VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_import_operator (operator_id,create_time),
  KEY idx_import_status (status,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TXT import previews and execution progress';
