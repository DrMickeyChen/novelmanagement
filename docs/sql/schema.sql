-- ============================================================
-- 小说管理系统 · 建表脚本
-- MySQL 8.0 / InnoDB / utf8mb4
--
-- 【用法】
--   ① 自动：把这个文件放进 ~/novel-docker/init/ ，MySQL 容器
--      【首次启动】时会自动执行（init 目录已挂在 docker-compose.yml 里）
--   ② 手动：库已经建好了才改这个文件的话，执行
--      sudo docker exec -i novel-mysql mysql -uroot -p密码 < schema.sql
--
-- 【共 21 张表】
--   RBAC 权限    sys_user  sys_role  sys_user_role  sys_menu  sys_role_menu
--   作品与阅读   novel  novel_category  novel_outline  chapter  chapter_content
--                novel_comment  user_shelf  read_history
--   运营与管理   author_apply  audit_log  sys_notice
--   AI 能力      ai_config  ai_prompt_template  ai_usage_log  ai_summary  ai_audit_result
--
-- 【★ 为什么全程不用外键约束（FOREIGN KEY）】
--   1. 本项目第二期要拆微服务，表会按服务切开，外键会挡住分库拆分
--   2. 外键把一致性检查压到数据库层，出问题时错误信息晦涩、排查困难
--   3. 状态流转的约束（见项目大纲 §5.3）本来就必须在 Service 层写，
--      靠外键只能挡住"引用了不存在的行"这一种情况，价值有限
--   所以：一致性由 Service 层保证，索引用来兜住查询性能。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `novel`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `novel`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


-- ============================================================
-- 一、RBAC 权限（5 张）
-- ============================================================

-- ------------------------------------------------------------
-- 1. sys_user —— 用户（读者 / 作者 / 管理员同表，靠角色区分）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `username`    VARCHAR(50)  NOT NULL                COMMENT '登录名',
  `password`    VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希，不是明文',
  `nickname`    VARCHAR(50)  DEFAULT NULL            COMMENT '昵称',
  `avatar`      VARCHAR(255) DEFAULT NULL            COMMENT '头像 URL',
  `email`       VARCHAR(100) DEFAULT NULL,
  `phone`       VARCHAR(20)  DEFAULT NULL,
  `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '1正常 0封禁',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';


-- ------------------------------------------------------------
-- 2. sys_role —— 角色
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `role_code`   VARCHAR(50) NOT NULL                 COMMENT '角色标识，如 ADMIN / AUTHOR / READER',
  `role_name`   VARCHAR(50) NOT NULL                 COMMENT '角色名',
  `remark`      VARCHAR(255) DEFAULT NULL,
  `status`      TINYINT     NOT NULL DEFAULT 1,
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';


-- ------------------------------------------------------------
-- 3. sys_user_role —— 用户-角色关联
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT   NOT NULL,
  `role_id`     BIGINT   NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-角色关联';


-- ------------------------------------------------------------
-- 4. sys_menu —— 菜单 / 权限点
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_menu` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `parent_id`   BIGINT       DEFAULT NULL            COMMENT '父菜单，NULL 为顶级',
  `name`        VARCHAR(50)  NOT NULL                COMMENT '菜单名',
  `type`        TINYINT      NOT NULL DEFAULT 2      COMMENT '1目录 2菜单 3按钮',
  `path`        VARCHAR(200) DEFAULT NULL            COMMENT '前端路由',
  `perms`       VARCHAR(100) DEFAULT NULL            COMMENT '权限标识，如 novel:audit',
  `icon`        VARCHAR(50)  DEFAULT NULL,
  `sort`        INT          NOT NULL DEFAULT 0,
  `visible`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1显示 0隐藏',
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_parent_sort` (`parent_id`, `sort`),
  KEY `idx_perms` (`perms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单/权限点';


-- ------------------------------------------------------------
-- 5. sys_role_menu —— 角色-菜单关联
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `role_id`     BIGINT   NOT NULL,
  `menu_id`     BIGINT   NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`),
  KEY `idx_menu_id` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-菜单关联';


-- ============================================================
-- 二、作品与阅读（7 张）
-- ============================================================

-- ------------------------------------------------------------
-- 6. novel_category —— 分类
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `novel_category` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(50) NOT NULL,
  `sort`        INT         NOT NULL DEFAULT 0,
  `status`      TINYINT     NOT NULL DEFAULT 1,
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='小说分类';


-- ------------------------------------------------------------
-- 7. novel —— 小说主表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `novel` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT,
  `title`            VARCHAR(100)  NOT NULL             COMMENT '书名',
  `cover`            VARCHAR(255)  DEFAULT NULL         COMMENT '封面 URL',
  `author_id`        BIGINT        NOT NULL             COMMENT '作者 sys_user.id',
  `category_id`      BIGINT        NOT NULL             COMMENT 'novel_category.id',
  `intro`            VARCHAR(1000) DEFAULT NULL         COMMENT '简介（给读者看的，不是大纲）',
  `tags`             VARCHAR(200)  DEFAULT NULL         COMMENT '逗号分隔，够用，不上 ES',
  `status`           TINYINT       NOT NULL DEFAULT 0   COMMENT '0草稿 1待审核 2已上架 3已下架 4已驳回',
  `audit_remark`     VARCHAR(255)  DEFAULT NULL         COMMENT '驳回理由',
  `word_count`       INT           NOT NULL DEFAULT 0   COMMENT '冗余：总字数',
  `chapter_count`    INT           NOT NULL DEFAULT 0   COMMENT '冗余：章节数',
  `view_count`       INT           NOT NULL DEFAULT 0   COMMENT '冗余：阅读量',
  `collect_count`    INT           NOT NULL DEFAULT 0   COMMENT '冗余：收藏数',
  `score`            DECIMAL(3,1)  NOT NULL DEFAULT 0.0 COMMENT '冗余：书评平均分',
  `is_finish`        TINYINT       NOT NULL DEFAULT 0   COMMENT '0连载 1完结',
  `last_chapter_id`  BIGINT        DEFAULT NULL         COMMENT '快速跳转最新章',
  `last_update_time` DATETIME      DEFAULT NULL         COMMENT '排序用',
  `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_category_status` (`category_id`, `status`),
  KEY `idx_author_id` (`author_id`),
  KEY `idx_status_update` (`status`, `last_update_time`),
  KEY `idx_title` (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='小说主表';


-- ------------------------------------------------------------
-- 8. novel_outline —— ★ 作品大纲树（核心表）
--
--    邻接表模型：parent_id 指父节点，全书的根节点 parent_id IS NULL
--    层级完全由 parent_id 决定，node_type 只是语义标签、不限制嵌套深度
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `novel_outline` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `novel_id`    BIGINT       NOT NULL,
  `parent_id`   BIGINT       DEFAULT NULL            COMMENT '父节点，NULL = 根（全书大纲）',
  `node_type`   TINYINT      NOT NULL DEFAULT 4      COMMENT '1全书 2卷 3章 4细纲/情节',
  `title`       VARCHAR(200) NOT NULL,
  `content`     TEXT         DEFAULT NULL            COMMENT '节点内容（大纲正文）',
  `chapter_id`  BIGINT       DEFAULT NULL            COMMENT '★ 可空：关联到实际章节，支持先写大纲后建正文',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '0未开始 1写作中 2已完成',
  `sort`        INT          NOT NULL DEFAULT 0      COMMENT '同级排序',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_novel_parent_sort` (`novel_id`, `parent_id`, `sort`),
  KEY `idx_chapter_id` (`chapter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作品大纲树';


-- ------------------------------------------------------------
-- 9. chapter —— 章节元信息（不含正文）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chapter` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `novel_id`     BIGINT       NOT NULL,
  `chapter_no`   INT          NOT NULL               COMMENT '第几章，重排时用',
  `title`        VARCHAR(200) NOT NULL,
  `word_count`   INT          NOT NULL DEFAULT 0,
  `status`       TINYINT      NOT NULL DEFAULT 0     COMMENT '0草稿 1待审核 2已发布 4已驳回',
  `audit_remark` VARCHAR(255) DEFAULT NULL,
  `is_free`      TINYINT      NOT NULL DEFAULT 1     COMMENT '预留 VIP 字段',
  `view_count`   INT          NOT NULL DEFAULT 0,
  `publish_time` DATETIME     DEFAULT NULL,
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_novel_chapter_no` (`novel_id`, `chapter_no`),
  KEY `idx_novel_status` (`novel_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='章节元信息';


-- ------------------------------------------------------------
-- 10. chapter_content —— 章节正文（大字段分离）
--
--     ★ 为什么单独一张表：目录页一次要查几十上百章，
--       正文和元信息同表的话 SELECT * 会把几百 KB 正文一起拖出来。
--       分离后目录查询只扫小表，只有打开具体章节才读正文。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chapter_content` (
  `chapter_id` BIGINT   NOT NULL                     COMMENT '与 chapter 一对一',
  `content`    LONGTEXT NOT NULL,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`chapter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='章节正文';


-- ------------------------------------------------------------
-- 11. novel_comment —— 评论（书评 + 章评，用 target_type 区分）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `novel_comment` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT        NOT NULL,
  `target_type`   TINYINT       NOT NULL             COMMENT '1书评 2章评',
  `target_id`     BIGINT        NOT NULL             COMMENT 'novel_id 或 chapter_id',
  `parent_id`     BIGINT        DEFAULT NULL         COMMENT '二级回复',
  `content`       VARCHAR(1000) NOT NULL,
  `score`         TINYINT       DEFAULT NULL         COMMENT '仅书评：1~5 分',
  `like_count`    INT           NOT NULL DEFAULT 0,
  `status`        TINYINT       NOT NULL DEFAULT 0   COMMENT '0待审 1通过 2驳回',
  `audit_user_id` BIGINT        DEFAULT NULL,
  `audit_time`    DATETIME      DEFAULT NULL,
  `audit_remark`  VARCHAR(255)  DEFAULT NULL,
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted`       TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`, `status`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论';


-- ------------------------------------------------------------
-- 12. user_shelf —— 书架收藏
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_shelf` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT   NOT NULL,
  `novel_id`    BIGINT   NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_novel` (`user_id`, `novel_id`),
  KEY `idx_novel_id` (`novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='书架收藏';


-- ------------------------------------------------------------
-- 13. read_history —— 阅读历史与进度
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `read_history` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT   NOT NULL,
  `novel_id`    BIGINT   NOT NULL,
  `chapter_id`  BIGINT   NOT NULL,
  `progress`    INT      NOT NULL DEFAULT 0          COMMENT '章内进度百分比或滚动位置',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 一本书一条记录，不断 UPDATE，不是每读一次插一条
  UNIQUE KEY `uk_user_novel` (`user_id`, `novel_id`),
  -- 历史列表按时间倒序；MySQL 8 支持真正的降序索引
  KEY `idx_user_time` (`user_id`, `update_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='阅读历史与进度';


-- ============================================================
-- 三、运营与管理（3 张）
-- ============================================================

-- ------------------------------------------------------------
-- 14. author_apply —— 作者申请
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `author_apply` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT       NOT NULL,
  `pen_name`      VARCHAR(50)  NOT NULL              COMMENT '笔名',
  `intro`         VARCHAR(500) DEFAULT NULL          COMMENT '创作说明',
  `status`        TINYINT      NOT NULL DEFAULT 0    COMMENT '0待审 1通过 2驳回',
  `audit_user_id` BIGINT       DEFAULT NULL,
  `audit_time`    DATETIME     DEFAULT NULL,
  `audit_remark`  VARCHAR(255) DEFAULT NULL,
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作者申请';


-- ------------------------------------------------------------
-- 15. audit_log —— 所有审核动作的流水（追责 + "我提交过什么"回显）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `target_type` TINYINT      NOT NULL                COMMENT '1小说 2章节 3评论 4作者申请',
  `target_id`   BIGINT       NOT NULL,
  `action`      TINYINT      NOT NULL                COMMENT '1提交 2通过 3驳回',
  `operator_id` BIGINT       NOT NULL                COMMENT '操作人',
  `remark`      VARCHAR(500) DEFAULT NULL,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_operator` (`operator_id`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审核流水';


-- ------------------------------------------------------------
-- 16. sys_notice —— 站内公告
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_notice` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `title`       VARCHAR(200) NOT NULL,
  `content`     TEXT         NOT NULL,
  `type`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1公告 2通知',
  `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '1发布 0下线',
  `publish_time` DATETIME    DEFAULT NULL,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`, `publish_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内公告';


-- ============================================================
-- 四、AI 能力（5 张）
-- ============================================================

-- ------------------------------------------------------------
-- 17. ai_config —— 全局唯一一条，仅管理员可改
--     ★ api_key 必须是 AES 加密后的密文，绝不存明文，
--       前端接口只返回掩码值（sk-****abcd），且只写不读
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_config` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `provider`    VARCHAR(20)  NOT NULL DEFAULT 'DEEPSEEK',
  `api_key`     VARCHAR(500) DEFAULT NULL            COMMENT '★ AES 加密存储，不是明文',
  `base_url`    VARCHAR(200) DEFAULT NULL            COMMENT 'DeepSeek 的 OpenAI 兼容端点',
  `model`       VARCHAR(50)  DEFAULT NULL            COMMENT '如 deepseek-chat',
  `temperature` DECIMAL(2,1) NOT NULL DEFAULT 0.7,
  `max_tokens`  INT          NOT NULL DEFAULT 2048,
  `daily_limit` INT          NOT NULL DEFAULT 50     COMMENT '每用户每日调用上限',
  `enabled`     TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 配置';


-- ------------------------------------------------------------
-- 18. ai_prompt_template —— 提示词模板
--     ★ 做成表而不是写死在代码里：改提示词不用重新发版
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_prompt_template` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `scene`         TINYINT     NOT NULL               COMMENT '1大纲 2正文 3灵感 4润色 5违禁审核',
  `name`          VARCHAR(50) NOT NULL,
  `system_prompt` TEXT        NOT NULL,
  `user_template` TEXT        NOT NULL               COMMENT '带占位符 {{novelTitle}} {{outline}} {{summary}} {{selected}}',
  `enabled`       TINYINT     NOT NULL DEFAULT 1,
  `sort`          INT         NOT NULL DEFAULT 0,
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scene_name` (`scene`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 提示词模板';


-- ------------------------------------------------------------
-- 19. ai_usage_log —— AI 调用记录与 token 消耗
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_usage_log` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT       NOT NULL,
  `scene`             TINYINT      NOT NULL,
  `novel_id`          BIGINT       DEFAULT NULL,
  `chapter_id`        BIGINT       DEFAULT NULL,
  `model`             VARCHAR(50)  DEFAULT NULL,
  `prompt_tokens`     INT          NOT NULL DEFAULT 0,
  `completion_tokens` INT          NOT NULL DEFAULT 0,
  `total_tokens`      INT          NOT NULL DEFAULT 0,
  `duration_ms`       INT          NOT NULL DEFAULT 0,
  `success`           TINYINT      NOT NULL DEFAULT 1,
  `error_msg`         VARCHAR(500) DEFAULT NULL,
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用记录';


-- ------------------------------------------------------------
-- 20. ai_summary —— 小说前情摘要（长文上下文压缩）
--     长篇写到几百章，不可能把全文塞进 prompt，用摘要滚动压缩
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_summary` (
  `id`               BIGINT   NOT NULL AUTO_INCREMENT,
  `novel_id`         BIGINT   NOT NULL,
  `start_chapter_no` INT      NOT NULL,
  `end_chapter_no`   INT      NOT NULL,
  `summary`          TEXT     NOT NULL,
  `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_novel_end` (`novel_id`, `end_chapter_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='小说前情摘要';


-- ------------------------------------------------------------
-- 21. ai_audit_result —— AI 违禁词审核结果
--     与 audit_log 的区别：这里是"机器判的"，audit_log 是"人做的"
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_audit_result` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `target_type`   TINYINT       NOT NULL             COMMENT '1小说 2章节 3评论',
  `target_id`     BIGINT        NOT NULL,
  `risk_level`    TINYINT       NOT NULL DEFAULT 0   COMMENT '0无风险 1低 2中 3高',
  `hit_words`     VARCHAR(500)  DEFAULT NULL         COMMENT '命中的关键词',
  `ai_comment`    VARCHAR(1000) DEFAULT NULL         COMMENT 'AI 给出的理由',
  `status`        TINYINT       NOT NULL DEFAULT 0   COMMENT '0待人工确认 1已确认违规 2已排除',
  `audit_user_id` BIGINT        DEFAULT NULL,
  `audit_time`    DATETIME      DEFAULT NULL,
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_risk` (`status`, `risk_level`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 违禁审核结果';


SET FOREIGN_KEY_CHECKS = 1;


SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- DDL 到此结束（21 张表）。
-- 初始数据（角色 / 管理员账号 / 分类 / AI 模板）在 seed.sql，
-- 两个文件按文件名顺序被 init 目录执行：schema.sql → seed.sql
-- ============================================================
