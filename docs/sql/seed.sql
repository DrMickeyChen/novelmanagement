-- ============================================================
-- 小说管理系统 · 初始数据
-- 依赖 schema.sql 先执行（表得先存在）
--
-- 【用法】
--   ① 自动：和 schema.sql 一起放进 ~/novel-docker/init/ ，
--      MySQL 容器【首次启动】会按文件名顺序先跑 schema.sql 再跑这个
--   ② 手动：sudo docker exec -i novel-mysql mysql -uroot -p密码 < seed.sql
--
-- 【特点】全部用 INSERT IGNORE + 显式 id ——
--   重复执行不会报错、不会产生脏数据，可以放心反复跑。
-- ============================================================

USE `novel`;
SET NAMES utf8mb4;


-- 五、初始数据
--
-- 用 INSERT IGNORE + 显式 id：重复执行不会报错、不会产生脏数据，
-- 你可以放心地反复跑这个文件。
-- ============================================================

-- ---------- 角色 ----------
INSERT IGNORE INTO `sys_role` (`id`, `role_code`, `role_name`, `remark`) VALUES
  (1, 'READER', '读者',   '默认角色，注册即拥有'),
  (2, 'AUTHOR', '作者',   '可创建作品、提交审核'),
  (3, 'ADMIN',  '管理员', '审核、运营、AI 配置');

-- ---------- 管理员账号 ----------
-- ★ 明文密码：admin123
--   下面这串是 BCrypt(admin123, cost=10) 的结果，格式 $2a$10$...
--   和 Spring Security 的 BCryptPasswordEncoder 完全兼容。
--   上线前务必改掉。
INSERT IGNORE INTO `sys_user` (`id`, `username`, `password`, `nickname`, `status`) VALUES
  (1, 'admin', '$2a$10$Jwayv0o7fFfQnySaxRQ7x.iujumvKLIbHjDfE3R2sL.EZbfBAZB3S', '系统管理员', 1);

INSERT IGNORE INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
  (1, 1, 3);

-- ---------- 菜单 / 权限点 ----------
INSERT IGNORE INTO `sys_menu` (`id`, `parent_id`, `name`, `type`, `path`, `perms`, `icon`, `sort`) VALUES
  (1,  NULL, '内容管理',   1, '/content',      NULL,                      'Document', 1),
  (2,  1,    '作品管理',   2, '/novel/list',   'content:novel:list',      'Reading',  1),
  (3,  1,    '审核中心',   2, '/audit',        'content:audit:list',      'Checked',  2),
  (4,  1,    '评论管理',   2, '/comment/list', 'content:comment:list',    'ChatDotRound', 3),
  (5,  1,    '分类管理',   2, '/category/list','content:category:list',   'Menu',     4),
  (6,  NULL, '用户管理',   1, '/system',       NULL,                      'User',     2),
  (7,  6,    '用户列表',   2, '/user/list',    'system:user:list',        'UserFilled', 1),
  (8,  6,    '作者申请',   2, '/author/apply', 'system:author:apply',     'EditPen',  2),
  (9,  6,    '角色管理',   2, '/role/list',    'system:role:list',        'Key',      3),
  (10, 6,    '菜单管理',   2, '/menu/list',    'system:menu:list',        'Grid',     4),
  (11, 6,    'AI 配置',    2, '/ai/config',    'system:ai:config',        'MagicStick', 5),
  (12, NULL, '运营管理',   1, '/ops',          NULL,                      'DataLine', 3),
  (13, 12,   '公告管理',   2, '/notice/list',  'ops:notice:list',         'Bell',     1),
  (14, 12,   '审核日志',   2, '/auditlog/list','ops:auditlog:list',       'List',     2),
  (15, 12,   '违禁审核',   2, '/ai/audit',     'ops:ai:audit:list',       'Warning',  3);

-- 管理员拥有全部菜单
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
  SELECT 3, `id` FROM `sys_menu`;

-- 作者只能看到内容管理下的作品相关
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES
  (2, 1), (2, 2);

-- ---------- 小说分类 ----------
INSERT IGNORE INTO `novel_category` (`id`, `name`, `sort`) VALUES
  (1, '玄幻', 1), (2, '仙侠', 2), (3, '都市', 3), (4, '历史', 4),
  (5, '科幻', 5), (6, '游戏', 6), (7, '悬疑', 7), (8, '言情', 8);

-- ---------- AI 配置（占位，API Key 由管理员在后台录入）----------
INSERT IGNORE INTO `ai_config` (`id`, `provider`, `api_key`, `base_url`, `model`, `temperature`, `max_tokens`, `daily_limit`, `enabled`) VALUES
  (1, 'DEEPSEEK', NULL, 'https://api.deepseek.com/v1', 'deepseek-chat', 0.7, 2048, 50, 0);
-- ★ enabled 先给 0：没配 API Key 之前不要让用户点到 AI 功能

-- ---------- AI 提示词模板 ----------
-- scene: 1大纲 2正文 3灵感 4润色 5违禁审核
INSERT IGNORE INTO `ai_prompt_template` (`id`, `scene`, `name`, `system_prompt`, `user_template`, `sort`) VALUES
  (1, 1, '默认大纲模板',
   '你是一位资深网文编辑，擅长规划长篇小说的结构。你的任务是帮作者设计分卷分章的大纲。要求：层级清晰、节奏张弛有度、每章都有明确的冲突或推进。',
   '作品名：《{{novelTitle}}》\n已有大纲：\n{{outline}}\n\n请在此基础上，为下面这个部分规划下一层级的子节点，输出严格的 JSON 数组。',
   1),

  (2, 2, '默认续写模板',
   '你是一位文笔流畅的网文作者。请续写下面的正文，保持人称、语气、节奏一致，不要复述已有内容，不要提前收束情节。',
   '作品名：《{{novelTitle}}》\n前情摘要：\n{{summary}}\n\n当前章节大纲：\n{{outline}}\n\n已写正文（结尾部分）：\n{{selected}}\n\n请自然地往下续写。',
   1),

  (3, 3, '默认灵感模板',
   '你是一位脑洞丰富的网文策划。请围绕作者当前的情节节点，给出几个具体、可落地、彼此差异明显的走向建议，避免套话。',
   '作品名：《{{novelTitle}}》\n前情摘要：\n{{summary}}\n\n当前节点：\n{{outline}}\n\n请给出 3~5 个不同的情节走向，每个用两三句话说明会发生什么、以及它的看点在哪。',
   1),

  (4, 4, '默认润色模板',
   '你是一位文字功底扎实的编辑。请在严格保留原意和情节的前提下润色用户选中的文字，提升画面感和节奏，不要增加新的情节。',
   '作品名：《{{novelTitle}}》\n\n需要润色的文字：\n{{selected}}\n\n请直接输出润色后的文字，不要解释你改了什么。',
   1),

  (5, 5, '默认违禁审核模板',
   '你是一名内容安全审核员。请判断给定文本是否包含违法违规、色情低俗、暴力血腥、涉政涉恐等内容。宁可放过轻微内容，也不要误伤正常情节描写。',
   '请审核以下文本：\n{{selected}}\n\n只输出 JSON：{"riskLevel": 0到3的整数, "hitWords": ["命中的词"], "comment": "判定理由"}',
   1);


-- ============================================================
-- 完成。21 张表 + 初始数据。
--
-- 验收：
--   USE novel;
--   SHOW TABLES;                                    -- 应该有 21 张
--   SELECT username FROM sys_user;                  -- admin
--   SELECT COUNT(*) FROM sys_menu;                  -- 15
--   SELECT COUNT(*) FROM ai_prompt_template;        -- 5
-- ============================================================

-- ============================================================
-- 验收（在 MySQL 里执行）：
--   SELECT username FROM sys_user;                      -- admin
--   SELECT COUNT(*) FROM sys_menu;                      -- 15
--   SELECT COUNT(*) FROM sys_role_menu;                 -- 17
--   SELECT COUNT(*) FROM novel_category;                -- 8
--   SELECT COUNT(*) FROM ai_prompt_template;            -- 5
--   SELECT enabled FROM ai_config;                      -- 0（配好 Key 再打开）
-- ============================================================
