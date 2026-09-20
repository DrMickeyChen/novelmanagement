package com.novel.modules.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 阅读进度，对应 {@code read_history}。<b>一个用户对一本书只有一行。</b>
 *
 * <pre>
 *   UNIQUE KEY uk_user_novel (user_id, novel_id)
 *   KEY idx_user_time (user_id, update_time DESC)
 * </pre>
 * {@code uk_user_novel} 是这张表的全部设计：<b>进度是「覆盖」语义，不是流水</b>。
 * 每滚动一屏就插一行的话，一个重度读者一本书就能写几万行，
 * 而产品要的只是「上次读到哪」。所以由数据库的唯一键来裁决「同一本书只留一条」——
 * 应用层不需要「先查有没有、有就更新」那套两分支逻辑。
 *
 * <p><b>★★ 这个实体有两个「缺字段」，都是故意的，加回去就会炸：</b>
 * <ol>
 *   <li><b>没有 {@code createTime}。</b>{@code read_history} 表<b>只有
 *       {@code update_time}，没有 {@code create_time} 列</b>。
 *       加上这个字段并标 {@code @TableField(fill = INSERT)}，
 *       {@code MybatisMetaObjectHandler} 就会把它塞进 INSERT 语句 →
 *       每一次进度上报都报 {@code Unknown column 'create_time' in 'field list'}。
 *       <br>概念上也说不通：这张表只有「最后读到哪」，没有「第一次读是什么时候」，
 *       加一个 create_time 等于凭空发明了一个产品没有的字段。</li>
 *   <li><b>没有 {@code @TableLogic} / {@code deleted}。</b>表里没有 {@code deleted} 列。
 *       删书、删章节都不需要删进度（书都没了，进度留着也查不出来 ——
 *       读者端进不去那本书）。真正要清进度时是物理删除。</li>
 * </ol>
 *
 * <p><b>★ 和 {@code AuditLog} 是同一类坑的两个方向，可以对着看：</b>
 * 那个表有 {@code create_time} 没 {@code update_time}，所以那个实体不能有
 * {@code updateTime}；这张表反过来。{@code MybatisMetaObjectHandler} 是无差别填充的 ——
 * 它对「这张表到底有没有这一列」一无所知，只能靠实体不写这个字段来规避。
 *
 * <p><b>★ {@code updateTime} 上没有 {@code @TableField(fill = ...)}，这也是故意的：</b>
 * 本模块<b>唯一的写路径</b>是 {@code ReadHistoryMapper#upsert}（一条手写 SQL），
 * 时间由调用方作为参数传进去。给字段挂上填充注解，会让人以为
 * {@code updateById} 之类的 MP 方法也能正确写入这一列 —— 能写，但写的是
 * 应用时间，和 upsert 传进来的那个值混在一起，时间的来源就有两个了。
 *
 * @author MickeyChen
 */
@Data
@TableName("read_history")
public class ReadHistory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 读者 {@code sys_user.id}。
     *
     * <p><b>★★ 只来自 {@code UserContext}，永远不从请求参数取。</b>
     * 这个字段一旦能被调用方指定，{@code GET /api/reader/history/{novelId}}
     * 就变成了「读任意用户的阅读进度」—— 典型的 IDOR。
     * 和 P2 的 {@code novel.authorId}、{@code audit_log.operatorId} 是同一条规矩。
     */
    private Long userId;

    private Long novelId;

    /** 读到哪一章。★ 上报时会校验它<b>属于这本作品且已发布</b>，见 HistoryServiceImpl */
    private Long chapterId;

    /**
     * 章内进度百分比，{@code 0~100}。
     *
     * <p>列类型是 {@code int NOT NULL DEFAULT 0}。
     * 写入前会夹到 {@code [0, 100]}（见 {@code HistoryServiceImpl#clampProgress}）——
     * 夹而不是报 400，理由见那里的注释。
     */
    private Integer progress;

    /**
     * 最后上报时间。<b>由应用写入</b>，不是数据库的
     * {@code ON UPDATE CURRENT_TIMESTAMP}（虽然建表语句里也有它）。
     *
     * <p>理由同 {@code MybatisMetaObjectHandler} 的类注释：数据库的
     * {@code CURRENT_TIMESTAMP} 用的是数据库服务器时区，应用用的是应用时区，
     * 统一由应用填，时间口径才只有一个。
     */
    private LocalDateTime updateTime;
}
