-- ============================================================
-- 小说管理系统 · 初始数据
-- 依赖 schema.sql 先执行（表得先存在）
--
-- 【用法】
--   ① 自动：和 schema.sql 一起放进 ~/novel-docker/init/ ，
--      MySQL 容器【首次启动】会按文件名顺序先跑 schema.sql 再跑这个
--   ② 手动：sudo docker exec -i novel-mysql mysql -uroot -p密码 < seed.sql
--
-- 【特点】全部用 INSERT IGNORE —— 重复执行不会报错、不会产生脏数据。
--
-- ★★ 但「可以放心反复跑」这句话有个例外，必须知道：
--   INSERT IGNORE 对【已存在的行】是「跳过」，对【要新增的行】也是「跳过」——
--   只要它撞上主键或唯一键。所以给一个旧库补一条【新数据】时，
--   如果那条数据写死了 id，而那个 id 已经被别人占了，
--   INSERT IGNORE 会一声不响地跳过，脚本输出一切正常，数据却没进去。
--   这个坑真踩过：author01 原来写死 id=2，而测试库里 id=2 早被 reader01 占了，
--   重跑 seed.sql 之后 author01 根本没建出来，也没有任何报错。
--   结论：**新增的数据行不要写死 id**，用业务唯一键（username / role_code）关联。
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

-- ---------- 测试账号 ----------
-- ★ 明文密码：admin → admin123，author01 → 123456
--   下面两串是 BCrypt(明文, cost=10) 的结果，格式 $2a$10$...
--   和 Spring Security 的 BCryptPasswordEncoder 完全兼容。
--   上线前务必改掉。
--
-- ★ 为什么这里直接灌一个作者账号，而不走「申请成为作者」的流程：
--   author_apply 那套申请 + 审核的流程是 P5 的内容，P2 只需要一个能调
--   创作端接口的身份。等 P5 做完，这个账号依然有用 —— 它是最省事的
--   回归测试账号（每次改完内容主干，用它跑一遍建书→提交→通过）。
--
-- ★ 别手抄网上找来的 BCrypt 哈希：不同 cost 值混用会让 BCryptPasswordEncoder
--   的 matches() 静默返回 false，症状是「密码明明是对的却登录失败」，
--   而且不报任何错，非常难查。这两串是用项目自己的编码器现算的。
-- ★★ 故意【不写 id】：靠 uk_username 这个业务唯一键来保证幂等。
--   写死 id 的后果见文件开头那段 —— 旧库上 id 早被注册用户占了，
--   INSERT IGNORE 会静默跳过，脚本「成功」但账号没建出来。
INSERT IGNORE INTO `sys_user` (`username`, `password`, `nickname`, `status`) VALUES
  ('admin',    '$2a$10$Jwayv0o7fFfQnySaxRQ7x.iujumvKLIbHjDfE3R2sL.EZbfBAZB3S', '系统管理员', 1),
  ('author01', '$2a$10$eQ27jDJZyUoI0VAMpOhfMuqRMreA3BU.p0LoPTrrEVgJhIjhGHF6W', '测试作者',   1);

-- ★ 同理不写 user_id / role_id 的字面量，改成按名字回查 ——
--   否则账号的 id 变了、角色表被重排过，这里就会挂到错误的人身上，
--   而且是【错得悄无声息】的：一个读者莫名其妙获得了管理员权限。
--   幂等由 uk_user_role(user_id, role_id) 这个唯一键保证。
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.`id`, r.`id`
FROM `sys_user` u
         JOIN `sys_role` r
WHERE (u.`username` = 'admin' AND r.`role_code` = 'ADMIN')
   OR (u.`username` = 'author01' AND r.`role_code` = 'AUTHOR');

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
  (15, 12,   '违禁审核',   2, '/ai/audit',     'ops:aiaudit:list',        'Warning',  3);

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
--   SELECT username FROM sys_user;                  -- admin, author01
--   SELECT COUNT(*) FROM sys_menu;                  -- 15
--   SELECT COUNT(*) FROM ai_prompt_template;        -- 5
-- ============================================================

-- ============================================================
-- 验收（在 MySQL 里执行）：
--   SELECT username FROM sys_user;                      -- admin, author01
--   SELECT COUNT(*) FROM sys_menu;                      -- 15
--   SELECT COUNT(*) FROM sys_role_menu;                 -- 17
--   SELECT COUNT(*) FROM novel_category;                -- 8
--   SELECT COUNT(*) FROM ai_prompt_template;            -- 5
--   SELECT enabled FROM ai_config;                      -- 0（配好 Key 再打开）
-- ============================================================
