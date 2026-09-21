package com.novel.modules.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.novel.common.enums.NovelStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 小说主表，对应 {@code novel}。
 *
 * <p><b>★★ {@link #status} 这一列只能通过 {@code NovelService#transitStatus} 修改。</b>
 * 大纲 §1345 原话：「状态机集中在 Service 的一个方法里，用 switch 显式校验，
 * 禁止各 Controller 直接改 status 字段」。Controller / 其他 Service 里出现
 * {@code novel.setStatus(...)} 就说明状态机被绕开了 —— 绕开的后果是
 * <b>审核日志会漏记</b>（日志写在状态机方法内部），从此「这本书被谁改过」查不出来。
 *
 * <p><b>★ 四个冗余字段（{@code word_count} / {@code chapter_count} /
 * {@code last_chapter_id} / {@code last_update_time}）：</b>
 * 它们不在这里维护，统一由 {@code NovelServiceImpl#syncNovelStat} 重算。
 * <b>不要写「加一减一」的增量更新</b> —— 增量要求每条增删路径都记得调且方向正确，
 * 「删一章忘了减 1」必然发生，而且漂移了没人会发现（页面显示 12 章、实际 13 章）。
 *
 * <p>注意<b>没有 {@code version} 列</b> —— 虽然 {@code MybatisPlusConfig} 里注册了
 * 乐观锁插件，但这张表用不了（插件靠 {@code @Version} 注解的字段工作，
 * 没有这一列就没法加）。P2 靠状态机的前置状态判断来防并发改状态。
 *
 * @author MickeyChen
 */
@Data
@TableName("novel")
public class Novel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 书名 */
    private String title;

    /** 封面 URL */
    private String cover;

    /** 作者 {@code sys_user.id}。★ 越权校验就是拿它和当前登录用户比 */
    private Long authorId;

    /** 分类 {@code novel_category.id} */
    private Long categoryId;

    /** 简介（给读者看的，不是大纲） */
    private String intro;

    /** 标签，逗号分隔 */
    private String tags;

    /** 状态机：0草稿 1待审核 2已上架 3已下架 4已驳回。★ 只能走 transitStatus 改 */
    private NovelStatus status;

    /** 驳回理由。★ 重新提交时会被清空，别指望它一直留着 */
    private String auditRemark;

    /** 冗余：已发布章节的总字数。由 syncNovelStat 重算 */
    private Integer wordCount;

    /** 冗余：已发布章节数。由 syncNovelStat 重算 */
    private Integer chapterCount;

    /** 冗余：阅读量。★ P2 不维护，P3 做阅读器时用 Redis 计数回写 */
    private Integer viewCount;

    /** 冗余：收藏数。★ P2 不维护，P6 做书架时更新 */
    private Integer collectCount;

    /** 冗余：书评平均分。★ P2 不维护，P6 做书评时更新 */
    private BigDecimal score;

    /** 0 连载，1 完结 */
    private Integer isFinish;

    /** 最新已发布章节 id，用于「继续阅读」快速跳转。由 syncNovelStat 重算 */
    private Long lastChapterId;

    /** 最后更新时间（有个已发布章节的时间）。★ 读者端按它倒序排「最近更新」 */
    private LocalDateTime lastUpdateTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
