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
--
-- ★★ 同一个「跳过」还有第二半，而且更隐蔽：【改了这个文件里已有行的值，重跑无效】。
--   本文件只 INSERT、不 UPDATE，所以库里的老行原封不动 ——
--   结果是「文件里是新值、库里还是旧值」，而脚本一声不响地跑完了。
--   S14 修 sys_menu.path（'/novel/list' → '/novel'，见下面菜单那一节）就撞上了：
--   改完文件，已有库里的那 11 行路径一条都没变，得**另外手工跑一条 UPDATE**。
--   所以规矩是：**改已有行的值 = 改文件 + 手工 UPDATE 那个库**，两件事都要做。
--   （刻意的：本文件不补一堆 UPDATE 来「自动对齐」。那会把它的语义从
--     「补种子数据」变成「声明式同步」，而后者要覆盖每一张表、每一列 ——
--     做不到就是更隐蔽的半同步，不如把边界写清楚。）
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
-- ★★ path 必须和 admin-web/src/router/index.js 里的【真实路由】逐字一致。
--   今天它还没有消费者（左侧菜单走的是 AdminLayout.vue 里那份写死的表，
--   按叶子的 perms 过滤），但两件事会让它变成真的：
--     ① 菜单管理页会把这一列显示出来 —— 值不对就是我们自己把假数据摆到界面上；
--     ② 二期的动态菜单要拿它当路由键。
-- ★★ S14 修过一次：原来这 12 个叶子里有 11 个写的是 '/x/list'，
--   那是照着**同一行**的 perms 抄形状抄出来的（content:novel:list），
--   把权限点结尾的 ':list' 误当成了路径层级。真实路由是 '/novel' 这种。
--   最离谱的是第 8 行：'/author/apply' 和真实路由 '/author-apply' 整段不同，
--   而且它会让人以为这一页属于「作者域」—— 它其实是**读者**在用的
--   （提交申请的人还不是作者），理由见 SecurityConfig 上的 D-7 那条注释。
--   9 / 13 两条页面 S14、S15 才做，这里的值按 AdminLayout 里已经写好的目标路径对齐。
-- ★ 目录行（1 / 6 / 12）的 path 只是【分组标识】，不对应任何路由 ——
--   它们是 AdminLayout 里 el-sub-menu 的分组标题，没有自己的页面。
INSERT IGNORE INTO `sys_menu` (`id`, `parent_id`, `name`, `type`, `path`, `perms`, `icon`, `sort`) VALUES
  (1,  NULL, '内容管理',   1, '/content',      NULL,                    'Document',     1),
  (2,  1,    '作品管理',   2, '/novel',        'content:novel:list',    'Reading',      1),
  (3,  1,    '审核中心',   2, '/audit',        'content:audit:list',    'Checked',      2),
  (4,  1,    '评论管理',   2, '/comment',      'content:comment:list',  'ChatDotRound', 3),
  (5,  1,    '分类管理',   2, '/category',     'content:category:list', 'Menu',         4),
  (6,  NULL, '用户管理',   1, '/system',       NULL,                    'User',         2),
  (7,  6,    '用户列表',   2, '/user',         'system:user:list',      'UserFilled',   1),
  (8,  6,    '作者申请',   2, '/author-apply', 'system:author:apply',   'EditPen',      2),
  (9,  6,    '角色管理',   2, '/role',         'system:role:list',      'Key',          3),
  (10, 6,    '菜单管理',   2, '/menu',         'system:menu:list',      'Grid',         4),
  (11, 6,    'AI 配置',    2, '/ai',           'system:ai:config',      'MagicStick',   5),
  (12, NULL, '运营管理',   1, '/ops',          NULL,                    'DataLine',     3),
  (13, 12,   '公告管理',   2, '/notice',       'ops:notice:list',       'Bell',         1),
  (14, 12,   '审核日志',   2, '/log',          'ops:auditlog:list',     'List',         2),
  (15, 12,   '违禁审核',   2, '/ai-audit',     'ops:aiaudit:list',      'Warning',      3);

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


-- ============================================================
-- P3 新增：读者端演示数据（12 本书 / 47 章 / 每章都有正文）
-- ============================================================
-- 为什么需要这一段：
--   P2 之前整个库只有分类、菜单、账号，【一条 novel / chapter 都没有】。
--   首页的轮播、榜单、搜索打开全是空的，P3 的 UI 根本没法验收。
--
-- id 分配（刻意避开 P2 测试数据用的 id=1、id=3）：
--   novel   1001 ~ 1012
--   chapter id = 小说 id * 100 + 章节序号   → 100101、100102 … 101203
--   chapter_content.chapter_id 与 chapter.id 相同
--   这样一眼就能看出某章属于哪本书的第几章，排查时不用来回查表。
--
-- ★★ 三条硬约束，改这一段时必须一起满足：
--
--   ① 冗余字段（novel 的 chapter_count / word_count / last_chapter_id /
--      last_update_time）【不能手写死值】，必须由下面第 4、5 步从 chapter
--      和 chapter_content 派生出来。
--      原因：P2 的 NovelServiceImpl.syncNovelStat 【只在章节状态流转时】被调用
--      （提交审核 / 通过 / 驳回 / 删除），直接 INSERT 的书永远不会触发它。
--      手写的话，一旦改了章节数量忘了同步，症状是详情页显示「0 章」但目录里
--      列着 5 章 —— 而且没有任何地方会报错。
--      派生出来就没这个问题：数据和元数据永远自洽。
--
--   ② 每一章都必须有 chapter_content 行。chapter_content 的主键是
--      chapter_id 且【非自增】，漏了不会报错，症状是「详情页有目录，
--      点进去正文空白」。
--
--   ③ 正文必须够长（本段生成的是 1800 字左右）。短于一个视口高度时
--      scrollHeight == clientHeight，阅读器的进度百分比恒为 100%，
--      验收「记住进度」时看起来像功能坏了。
--      正文用 REPEAT 循环生成而不是手写 47 段散文：这个文件的用途是
--      把数据形状灌对，不是提供可读小说。重复次数按 chapter_no 取模微调，
--      这样各章长短不一（真实数据也不会一样长）。
--
-- ★ 全部用 INSERT IGNORE，重复执行不报错 ——【本段可以直接重跑】。
--   每步 INSERT 后面紧跟一句 SHOW WARNINGS（它只看紧邻的上一条语句，
--   所以不能写在末尾），末尾再跟一段自检 SQL。重跑时看到 1062
--   Duplicate entry 是正常的；要警惕 1048（author01 没先建出来）
--   和 1406（简介/标签超长），以及自检里 A0 的条数不对。
-- ============================================================

-- author01 的 id 不写死：换了环境、重灌过库，id 都可能不一样。
-- ★ 如果它是 NULL（author01 没建出来），下面 12 本书会因为 author_id
--   非空约束被 INSERT IGNORE 【静默跳过】—— 脚本照样显示成功。
--   所以末尾的自检里有一条专门查「书是不是真的进去了」。
SET @author_id = (SELECT id FROM sys_user WHERE username = 'author01' AND deleted = 0);

-- ---------- 1) 12 本书 ----------
-- 覆盖全部 8 个分类（玄幻/仙侠/都市/历史 各 2 本，其余各 1 本），
-- is_finish = 1 的正好 2 本（1005、1009）—— 完结榜要有内容。
-- view_count 给非 0 初值，否则热门榜全是 0，看不出排序对不对。
-- create_time 按天数错开，新书榜才有区分度（1003 → 1006 → 1012 最新）。
INSERT IGNORE INTO `novel`
  (`id`, `title`, `author_id`, `category_id`, `intro`, `tags`,
   `status`, `view_count`, `is_finish`, `create_time`) VALUES

  (1001, '九霄剑主', @author_id, 1,
   '落霞山上有个不成器的少年，师父说他这辈子连剑都握不稳。直到他在后山剑冢里遇见了一个死了三百年的老人。',
   '玄幻,热血,升级', 2, 1280, 0, DATE_SUB(NOW(), INTERVAL 40 DAY)),

  (1002, '青冥问道', @author_id, 2,
   '青冥山下十年一场的收徒大典，来了一万七千人，只收三个。他是第一个被赶下山的。',
   '仙侠,问道,慢热', 2, 960, 0, DATE_SUB(NOW(), INTERVAL 32 DAY)),

  (1003, '深夜便利店', @author_id, 3,
   '凌晨三点的便利店，进来的人只买两样东西：热的，和便宜的。而他知道，他们其实都是来避雨的。',
   '都市,治愈,短篇', 2, 410, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),

  (1004, '长安十二楼', @author_id, 4,
   '天宝十四载，长安西市最热闹的一条街上，有十二座酒楼。没有人知道，其中一座的地基底下埋着什么。',
   '历史,权谋,长安', 2, 1520, 0, DATE_SUB(NOW(), INTERVAL 25 DAY)),

  (1005, '第七次日落', @author_id, 5,
   '观测站记录到第七次日出的时候，所有人都沉默了。因为那颗行星的一天，只有四十分钟。',
   '科幻,硬核,末日', 2, 730, 1, DATE_SUB(NOW(), INTERVAL 90 DAY)),

  (1006, '全服公敌', @author_id, 6,
   '开服第一天，他因为一个谁都看不懂的操作，被全服通缉。而他自己也不知道，那一下到底触发了什么。',
   '游戏,网游,爽文', 2, 620, 0, DATE_SUB(NOW(), INTERVAL 5 DAY)),

  (1007, '雾港来信', @author_id, 7,
   '雾港的邮局有一条规定：没有寄件人的信，一律销毁。而那天早上，柜台上摆了十七封。',
   '悬疑,推理,群像', 2, 1100, 0, DATE_SUB(NOW(), INTERVAL 12 DAY)),

  (1008, '晚风记得你', @author_id, 8,
   '她记得那天便利店的伞只剩一把，而他记得的，是她把伞塞过来时说的那句「我住得近」。',
   '言情,青春,治愈', 2, 890, 0, DATE_SUB(NOW(), INTERVAL 18 DAY)),

  (1009, '山海拾遗录', @author_id, 1,
   '山海之间散落着被世人遗忘的东西：断掉的名字、走失的影子、没说完的话。有人专门去把它们捡回来。',
   '玄幻,志怪,单元剧', 2, 350, 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),

  (1010, '咖啡与旧唱片', @author_id, 3,
   '巷子最深处那家店只做两件事：手冲，和放唱片。老板说，选唱片比选咖啡豆难多了。',
   '都市,日常,群像', 2, 540, 0, DATE_SUB(NOW(), INTERVAL 8 DAY)),

  (1011, '剑落沧溟', @author_id, 2,
   '沧溟渡口的水，三百年没有结过冰。直到那一年冬天，有人把一把断剑插进了河心。',
   '仙侠,剑修,悲情', 2, 1340, 0, DATE_SUB(NOW(), INTERVAL 15 DAY)),

  (1012, '大明匠户', @author_id, 4,
   '匠籍是世袭的，脱不掉。他父亲一辈子做了三千把椅子，而他想做一把不一样的。',
   '历史,匠心,种田', 2, 280, 0, DATE_SUB(NOW(), INTERVAL 3 DAY));

-- ★ SHOW WARNINGS 必须【贴着】它要检查的那条语句放 —— 它只反映紧邻的
--   上一条语句产生的警告，中间插了任何别的语句就看不到了。
SHOW WARNINGS;   -- 期望空。见下方 1048 / 1406 的说明


-- ---------- 2) 47 章 ----------
-- 书名号、章节序号、publish_time 都按「最后一章最近」排列，
-- 这样 last_update_time 派生出来各书不同，首页的「最近更新」才有区分度。
-- word_count 先留默认 0，第 4 步从正文派生。
INSERT IGNORE INTO `chapter`
  (`id`, `novel_id`, `chapter_no`, `title`, `status`, `publish_time`, `view_count`) VALUES

  -- 1001 九霄剑主（玄幻 · 5 章 · 最新一章 96 小时前）
  (100101, 1001, 1, '第一章 落霞山上的少年', 2, DATE_SUB(NOW(), INTERVAL 120 HOUR), 132),
  (100102, 1001, 2, '第二章 剑冢里的老人',   2, DATE_SUB(NOW(), INTERVAL 114 HOUR), 118),
  (100103, 1001, 3, '第三章 三招之约',       2, DATE_SUB(NOW(), INTERVAL 108 HOUR), 104),
  (100104, 1001, 4, '第四章 下山',           2, DATE_SUB(NOW(), INTERVAL 102 HOUR),  96),
  (100105, 1001, 5, '第五章 九霄令',         2, DATE_SUB(NOW(), INTERVAL  96 HOUR),  88),

  -- 1002 青冥问道（仙侠 · 4 章 · 72 小时前）
  (100201, 1002, 1, '第一章 青冥山下',       2, DATE_SUB(NOW(), INTERVAL  90 HOUR), 121),
  (100202, 1002, 2, '第二章 拜入山门',       2, DATE_SUB(NOW(), INTERVAL  84 HOUR), 110),
  (100203, 1002, 3, '第三章 第一场雪',       2, DATE_SUB(NOW(), INTERVAL  78 HOUR),  92),
  (100204, 1002, 4, '第四章 问道石前',       2, DATE_SUB(NOW(), INTERVAL  72 HOUR),  85),

  -- 1003 深夜便利店（都市 · 3 章 · 12 小时前 —— 最近更新第一名）
  (100301, 1003, 1, '第一章 凌晨三点的客人', 2, DATE_SUB(NOW(), INTERVAL  24 HOUR),  64),
  (100302, 1003, 2, '第二章 关东煮与旧照片', 2, DATE_SUB(NOW(), INTERVAL  18 HOUR),  57),
  (100303, 1003, 3, '第三章 她说她叫阿雪',   2, DATE_SUB(NOW(), INTERVAL  12 HOUR),  41),

  -- 1004 长安十二楼（历史 · 5 章 · 48 小时前）
  (100401, 1004, 1, '第一章 西市的胡商',     2, DATE_SUB(NOW(), INTERVAL  72 HOUR), 168),
  (100402, 1004, 2, '第二章 十二楼夜宴',     2, DATE_SUB(NOW(), INTERVAL  66 HOUR), 151),
  (100403, 1004, 3, '第三章 一封密信',       2, DATE_SUB(NOW(), INTERVAL  60 HOUR), 143),
  (100404, 1004, 4, '第四章 朱雀门下的雪',   2, DATE_SUB(NOW(), INTERVAL  54 HOUR), 129),
  (100405, 1004, 5, '第五章 楼塌了',         2, DATE_SUB(NOW(), INTERVAL  48 HOUR), 118),

  -- 1005 第七次日落（科幻 · 4 章 · 完结 · 240 小时前）
  (100501, 1005, 1, '第一章 第七次日出',     2, DATE_SUB(NOW(), INTERVAL 258 HOUR), 133),
  (100502, 1005, 2, '第二章 观测站的信号',   2, DATE_SUB(NOW(), INTERVAL 252 HOUR), 126),
  (100503, 1005, 3, '第三章 编号 K-17',      2, DATE_SUB(NOW(), INTERVAL 246 HOUR), 119),
  (100504, 1005, 4, '第四章 最后一次日落',   2, DATE_SUB(NOW(), INTERVAL 240 HOUR), 111),

  -- 1006 全服公敌（游戏 · 3 章 · 36 小时前）
  (100601, 1006, 1, '第一章 开服第一天',     2, DATE_SUB(NOW(), INTERVAL  48 HOUR),  77),
  (100602, 1006, 2, '第二章 全服通缉',       2, DATE_SUB(NOW(), INTERVAL  42 HOUR),  69),
  (100603, 1006, 3, '第三章 屠龙者',         2, DATE_SUB(NOW(), INTERVAL  36 HOUR),  58),

  -- 1007 雾港来信（悬疑 · 4 章 · 18 小时前）
  (100701, 1007, 1, '第一章 没有寄件人的信', 2, DATE_SUB(NOW(), INTERVAL  36 HOUR), 142),
  (100702, 1007, 2, '第二章 雾里的灯塔',     2, DATE_SUB(NOW(), INTERVAL  30 HOUR), 131),
  (100703, 1007, 3, '第三章 第二个失踪者',   2, DATE_SUB(NOW(), INTERVAL  24 HOUR), 124),
  (100704, 1007, 4, '第四章 潮水退去之后',   2, DATE_SUB(NOW(), INTERVAL  18 HOUR), 107),

  -- 1008 晚风记得你（言情 · 4 章 · 60 小时前）
  (100801, 1008, 1, '第一章 转角的便利店',   2, DATE_SUB(NOW(), INTERVAL  78 HOUR), 115),
  (100802, 1008, 2, '第二章 一把伞的距离',   2, DATE_SUB(NOW(), INTERVAL  72 HOUR), 108),
  (100803, 1008, 3, '第三章 台风天',         2, DATE_SUB(NOW(), INTERVAL  66 HOUR),  97),
  (100804, 1008, 4, '第四章 晚风记得你',     2, DATE_SUB(NOW(), INTERVAL  60 HOUR),  90),

  -- 1009 山海拾遗录（玄幻 · 3 章 · 完结 · 480 小时前）
  (100901, 1009, 1, '第一章 拾遗人',         2, DATE_SUB(NOW(), INTERVAL 492 HOUR),  52),
  (100902, 1009, 2, '第二章 会说话的铜镜',   2, DATE_SUB(NOW(), INTERVAL 486 HOUR),  47),
  (100903, 1009, 3, '第三章 归墟（完）',     2, DATE_SUB(NOW(), INTERVAL 480 HOUR),  39),

  -- 1010 咖啡与旧唱片（都市 · 4 章 · 24 小时前）
  (101001, 1010, 1, '第一章 巷子深处的店',   2, DATE_SUB(NOW(), INTERVAL  42 HOUR),  73),
  (101002, 1010, 2, '第二章 没有封套的唱片', 2, DATE_SUB(NOW(), INTERVAL  36 HOUR),  66),
  (101003, 1010, 3, '第三章 常客',           2, DATE_SUB(NOW(), INTERVAL  30 HOUR),  61),
  (101004, 1010, 4, '第四章 打烊之后',       2, DATE_SUB(NOW(), INTERVAL  24 HOUR),  54),

  -- 1011 剑落沧溟（仙侠 · 5 章 · 6 小时前 —— 最近更新第一名）
  (101101, 1011, 1, '第一章 沧溟渡口',       2, DATE_SUB(NOW(), INTERVAL  30 HOUR), 156),
  (101102, 1011, 2, '第二章 一剑断江',       2, DATE_SUB(NOW(), INTERVAL  24 HOUR), 149),
  (101103, 1011, 3, '第三章 故人来',         2, DATE_SUB(NOW(), INTERVAL  18 HOUR), 137),
  (101104, 1011, 4, '第四章 剑折',           2, DATE_SUB(NOW(), INTERVAL  12 HOUR), 128),
  (101105, 1011, 5, '第五章 落剑成渊',       2, DATE_SUB(NOW(), INTERVAL   6 HOUR), 112),

  -- 1012 大明匠户（历史 · 3 章 · 48 小时前）
  -- ★ 这本书的 novel.create_time 只比现在早 3 天（新书榜要它排第三），
  --   所以章节的 publish_time 必须落在 3 天以内 —— 章节不可能在书之前存在。
  --   参见末尾 A8。
  (101201, 1012, 1, '第一章 匠籍',           2, DATE_SUB(NOW(), INTERVAL  60 HOUR),  44),
  (101202, 1012, 2, '第二章 工部的差事',     2, DATE_SUB(NOW(), INTERVAL  54 HOUR),  38),
  (101203, 1012, 3, '第三章 一把椅子',       2, DATE_SUB(NOW(), INTERVAL  48 HOUR),  31);

SHOW WARNINGS;   -- 期望空


-- ---------- 3) 正文（每章一行，由 chapter 派生，不用手写 47 段） ----------
-- ★ 用 INSERT ... SELECT 而不是 47 行 VALUES：正文是从 chapter 表推出来的，
--   章节增删时这里不用跟着改，也不会漏。
-- ★ 长度约 1800 字（4 个段落块，重复次数按 chapter_no 取模微调，
--   让各章长短不一）。够长的目的是让 scrollHeight 远大于 clientHeight ——
--   否则阅读器的进度百分比恒为 100%，「记住进度」没法验收。
-- ★ 首行放章节标题，这样每章的开头不一样，滚动定位时肉眼能分辨。
INSERT IGNORE INTO `chapter_content` (`chapter_id`, `content`)
SELECT c.id,
       CONCAT(
         '　　', c.title, '\n\n',
         REPEAT('　　夜色像一块浸了水的旧棉布，沉沉地压在青瓦上。巷口那盏风灯晃了晃，把墙上两个人的影子拉长又揉碎。他停下脚步，听见更夫的梆子声从街尾传来，一下，两下，三下，敲得人心口发紧。\n\n',
                6 + (c.chapter_no % 3)),
         REPEAT('　　风穿过巷子，卷起地上的碎纸片，打着旋儿贴到墙根。他想起许多年前的那个傍晚，也是这样的天色，也是这样的风，只是那时候身边还站着另一个人，而那个人如今连名字都不太敢提起了。\n\n',
                5 + (c.chapter_no % 2)),
         REPEAT('　　远处的钟楼敲过九下。城里的灯火一盏接一盏地亮起来，又有人家开始生火做饭。炊烟顺着瓦缝往上爬，在暮色里散成一片模糊的灰，把整条街都罩了进去。\n\n',
                5 + (c.chapter_no % 4)),
         REPEAT('　　他把手插进袖子里，慢慢地往前走。路面上的水洼映着天光，一脚踩下去，碎成满地的星星。身后传来一声很轻的响动，像是有人跟了上来，又像只是风。\n\n',
                5)
       )
FROM `chapter` c
WHERE c.novel_id BETWEEN 1001 AND 1012
  AND c.status = 2
  AND c.deleted = 0;

SHOW WARNINGS;   -- 期望空


-- ---------- 4) 从正文派生 chapter.word_count ----------
-- 和后端 ChapterServiceImpl 的口径保持一致：去掉空白字符后数字符。
-- ★ 这里只去掉 \n，不去掉全角空格 —— 因为 Java 的 \s 等价于
--   [ \t\n\x0B\f\r]，【不包含全角空格 U+3000】，而正文的缩进用的正是
--   两个全角空格。两边都不去，口径才对得上。
UPDATE `chapter` c
JOIN `chapter_content` cc ON cc.chapter_id = c.id
SET c.word_count = CHAR_LENGTH(REPLACE(cc.content, '\n', ''))
WHERE c.novel_id BETWEEN 1001 AND 1012;


-- ---------- 4b) 把 chapter.create_time 拉回 publish_time 之前 ----------
-- ★ 第 2 步的 INSERT 没给 create_time，它走了列默认值 CURRENT_TIMESTAMP ——
--   于是每一章的「创建时间」都是灌数据的这一刻，而 publish_time 是几天前。
--   结果就是【创建时间晚于发布时间】，任何人翻这张表都会当成脏数据。
--   功能上无害（没有任何查询按 create_time 排章节），但正确性上说不通。
-- ★ 取 publish_time 前一小时而不是直接相等：章节正常是「先建草稿、后发布」，
--   留一小时既讲得通，又保证同一本书内 create_time 仍随 chapter_no 递增。
--   这一小时不会早于所属小说的 create_time（最早的一章也在小说创建之后）。
UPDATE `chapter` c
SET c.create_time = DATE_SUB(c.publish_time, INTERVAL 1 HOUR)
WHERE c.novel_id BETWEEN 1001 AND 1012;


-- ---------- 5) 从 chapter 派生 novel 的四个冗余字段 ----------
-- ★★ 这一步是整段的关键。P2 的 syncNovelStat 只在章节状态流转时跑，
--    直接 INSERT 的书永远不会触发它 —— 手写死值的话，改了章节忘了同步
--    就会出现「详情页 0 章、目录里 5 章」这种不报错的错位。
--    派生出来，两个数字永远自洽。
-- ★ 口径必须和 syncNovelStat 一致：只统计 status = 2（已发布）的章节，
--    且排除 deleted = 1。草稿章、驳回章不计入。
UPDATE `novel` n
JOIN (
    SELECT c.novel_id,
           COUNT(*)                        AS cnt,
           COALESCE(SUM(c.word_count), 0)  AS words
    FROM `chapter` c
    WHERE c.novel_id BETWEEN 1001 AND 1012
      AND c.status = 2
      AND c.deleted = 0
    GROUP BY c.novel_id
) t ON t.novel_id = n.id
SET n.chapter_count    = t.cnt,
    n.word_count       = t.words,
    n.last_chapter_id  = (SELECT c2.id FROM `chapter` c2
                          WHERE c2.novel_id = n.id AND c2.status = 2 AND c2.deleted = 0
                          ORDER BY c2.chapter_no DESC LIMIT 1),
    n.last_update_time = (SELECT MAX(c3.publish_time) FROM `chapter` c3
                          WHERE c3.novel_id = n.id AND c3.status = 2 AND c3.deleted = 0)
WHERE n.id BETWEEN 1001 AND 1012
  AND n.deleted = 0;


-- ---------- 6) 自检 ----------
-- ★ 关于警告：INSERT IGNORE 把「主键冲突」和「真正的数据问题」都降级成了
--   warning，不看 SHOW WARNINGS 就分不出来。但 SHOW WARNINGS 只反映
--   【紧邻的上一条语句】，写在最后什么也看不到 —— 所以上面每一步 INSERT
--   后面都紧跟着一句。重新执行本段时会看到一堆：
--     1062 Duplicate entry  → 【正常】，重复数据被跳过了
--   要警惕的是这两类，它们的含义完全不同：
--     1048 Column 'author_id' cannot be null  → author01 没先建出来
--     1406 Data too long                      → 简介/标签比列定义长

-- 下面每一条都期望返回 0 行（除了 A0 和 A5 是计数断言、A4b 是提示）。
-- ★ 用 <=>（NULL 安全等号）而不是 =：字段为 NULL 时 `NULL = 0` 结果是 NULL，
--   HAVING 判假 → 漏报，而漏报的正是最需要发现的那种脏数据。

-- A0 条数断言（期望 12 / 47 / 47）。
--    这是最直接的一道闸：INSERT IGNORE 静默跳过时没有任何报错，
--    只有数不对能暴露它。最常见的原因是 @author_id 为 NULL。
SELECT
  (SELECT COUNT(*) FROM `novel`           WHERE id BETWEEN 1001 AND 1012)          AS novels_should_be_12,
  (SELECT COUNT(*) FROM `chapter`         WHERE novel_id BETWEEN 1001 AND 1012)    AS chapters_should_be_47,
  (SELECT COUNT(*) FROM `chapter_content` WHERE chapter_id BETWEEN 100101 AND 101299) AS contents_should_be_47;

-- A1 chapter_count 漂移
SELECT n.id, n.chapter_count, COUNT(c.id) AS real_cnt FROM `novel` n
LEFT JOIN `chapter` c ON c.novel_id = n.id AND c.status = 2 AND c.deleted = 0
WHERE n.deleted = 0 GROUP BY n.id, n.chapter_count
HAVING (n.chapter_count <=> COUNT(c.id)) = 0;

-- A2 word_count 漂移
SELECT n.id, n.word_count, COALESCE(SUM(c.word_count), 0) AS real_words FROM `novel` n
LEFT JOIN `chapter` c ON c.novel_id = n.id AND c.status = 2 AND c.deleted = 0
WHERE n.deleted = 0 GROUP BY n.id, n.word_count
HAVING (n.word_count <=> COALESCE(SUM(c.word_count), 0)) = 0;

-- A3 last_chapter_id 漂移（含指向已删章节的情况）
SELECT n.id, n.last_chapter_id, t.mx_no FROM `novel` n
LEFT JOIN (SELECT novel_id, MAX(chapter_no) mx_no FROM `chapter`
           WHERE status = 2 AND deleted = 0 GROUP BY novel_id) t ON t.novel_id = n.id
LEFT JOIN `chapter` lc ON lc.id = n.last_chapter_id
WHERE n.deleted = 0 AND (lc.chapter_no <=> t.mx_no) = 0;

-- A4 八个种子分类都必须有已上架的书（期望 0 行）
--    ★ 限定 id 1~8。不限定的话，P2 验收在库里留下的临时分类（id 9~12）
--      会跟着一起报出来，把真正的问题淹掉。
SELECT c.id, c.name FROM `novel_category` c
LEFT JOIN `novel` n ON n.category_id = c.id AND n.status = 2 AND n.deleted = 0
WHERE c.id BETWEEN 1 AND 8 AND c.deleted = 0
GROUP BY c.id, c.name HAVING COUNT(n.id) = 0;

-- A4b 【提示，不是失败】还开着、却一本书都没有的分类。
--     这些会出现在读者端首页的分类入口里，点进去是空列表 ——
--     是 P2 验收留下的临时分类（种子数据管不着它们，要清自己清）。
SELECT c.id, c.name FROM `novel_category` c
LEFT JOIN `novel` n ON n.category_id = c.id AND n.status = 2 AND n.deleted = 0
WHERE c.deleted = 0 AND c.status = 1 AND c.id NOT BETWEEN 1 AND 8
GROUP BY c.id, c.name HAVING COUNT(n.id) = 0;

-- A5 完结书数量（期望 2，完结榜要有内容）
SELECT COUNT(*) AS finished_should_be_2 FROM `novel`
WHERE id BETWEEN 1001 AND 1012 AND status = 2 AND is_finish = 1 AND deleted = 0;

-- A6 ★ 已发布章节缺正文（最容易漏的一条，期望 0 行）
SELECT c.id, c.chapter_no FROM `chapter` c
LEFT JOIN `chapter_content` cc ON cc.chapter_id = c.id
WHERE c.novel_id BETWEEN 1001 AND 1012 AND c.status = 2 AND c.deleted = 0
  AND cc.chapter_id IS NULL;

-- A7 正文过短（会导致阅读器进度恒为 100%，期望 0 行）
SELECT cc.chapter_id, CHAR_LENGTH(cc.content) AS len FROM `chapter_content` cc
WHERE cc.chapter_id BETWEEN 100101 AND 101299 AND CHAR_LENGTH(cc.content) < 800;

-- A8 ★ 章节早于它所属的小说诞生（期望 0 行）。
--    两处时间轴是分别手写的（novel 按「几天前」、chapter 按「几小时前」），
--    很容易像 1012 那样对不上：书只存在了 3 天，章节却有 13 天的发布史。
--    ★ 这个错【不会触发任何约束、也不会让任何接口报错】，只能靠这条断言发现。
SELECT c.id, c.novel_id, c.create_time AS chapter_ct, n.create_time AS novel_ct
FROM `chapter` c JOIN `novel` n ON n.id = c.novel_id
WHERE c.novel_id BETWEEN 1001 AND 1012 AND c.create_time < n.create_time;

-- A9 章节的创建时间不早于发布时间（期望 0 行）。
--    由第 4b 步保证，这里只是钉死它 —— 反过来就是「先发布后创建」。
SELECT c.id, c.create_time, c.publish_time FROM `chapter` c
WHERE c.novel_id BETWEEN 1001 AND 1012 AND c.create_time >= c.publish_time;
-- ============================================================
