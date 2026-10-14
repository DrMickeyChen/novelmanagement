-- 在小说数据库中执行；独立保存向量配置，不修改现有 ai_config 聊天配置。
CREATE TABLE IF NOT EXISTS ai_embedding_config (
  id TINYINT NOT NULL PRIMARY KEY,
  url VARCHAR(500) NOT NULL,
  model VARCHAR(120) NOT NULL,
  api_key TEXT NULL COMMENT 'AiCrypto AES/GCM ciphertext only',
  batch_size INT NOT NULL DEFAULT 8,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
