package com.novel.modules.interaction.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 书架收藏，对应 {@code user_shelf}。<b>一个用户对一本书最多一行。</b>
 *
 * <pre>
 *   UNIQUE KEY uk_user_novel (user_id, novel_id)
 *   KEY idx_novel_id (novel_id)
 * </pre>
 * 和 {@code read_history} 一样，{@code uk_user_novel} 承担了全部去重语义：
 * 「加入书架」由数据库裁决「同一本书只留一条」，应用层不需要
 * 「先查有没有、有就插入」那套两分支逻辑（那个写法有 TOCTOU，见 Mapper 的注释）。
 *
 * <p>★ {@code idx_novel_id} 是给「重算 {@code novel.collect_count}」用的 ——
 * {@code SELECT COUNT(*) FROM user_shelf WHERE novel_id = ?} 走它做一次索引范围扫描。
 *
 * <p><b>★★ 这个实体的两个「缺字段」是故意的，加回去就会炸：</b>
 * <ol>
 *   <li><b>没有 {@code updateTime}。</b>表里只有 {@code create_time}，<b>没有
 *       {@code update_time} 列</b>。而 {@code MybatisMetaObjectHandler#updateFill} 是
 *       <b>无差别</b>填充的（它不知道表里有没有那一列），一旦实体上有这个字段，
 *       任何 {@code updateById} / {@code update(entity, wrapper)} 都会把
 *       {@code update_time = ?} 拼进 SQL → {@code Unknown column 'update_time'}。
 *       <br>这也是本模块删数据时要写 {@code update(null, wrapper)} 的原因。</li>
 *   <li><b>没有 {@code @TableLogic} / {@code deleted}。</b>表里没有 {@code deleted} 列
 *       （和 {@code read_history} 规则相同，与 {@code sys_role} / {@code novel_comment} 相反）。
 *       「移出书架」是<b>物理删除</b>，而且必须是这样：这个表的语义是「现在在不在架」，
 *       逻辑删会留下无数行「曾经加过又移出」的垃圾，去重还得再加一个条件。</li>
 * </ol>
 * ★ 同样地，手写这个表的 SQL 时<b>不要顺手补 {@code deleted = 0}</b> ——
 * 那会立刻 {@code Unknown column 'deleted'}。这条惯性是从别的表带过来的。
 *
 * <p><b>★★ {@code userId} 只来自 {@code UserContext}，永远不从请求参数取。</b>
 * 一旦能被调用方指定，「我的书架」就变成了「读任意用户的书架」—— 典型的 IDOR。
 * 同 {@code ReadHistory#userId}、{@code novel.authorId}、{@code audit_log.operatorId}。
 *
 * @author MickeyChen
 */
@Data
@TableName("user_shelf")
public class UserShelf implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 读者 {@code sys_user.id}。★ 只来自 UserContext */
    private Long userId;

    /** 收藏的作品 {@code novel.id} */
    private Long novelId;

    /**
     * 加入书架的时间。
     *
     * <p>★ 用应用时间填充而不是靠列的 {@code DEFAULT CURRENT_TIMESTAMP}：
     * 数据库在另一台机器（{@code 192.168.127.137}）上，两个时区口径可能不一致；
     * 统一由应用填，「现在几点」全项目才只有一个答案。
     * 同 {@code ReadHistory#updateTime} 的理由。
     *
     * <p>★ 重复加入<b>不刷新</b>这一列（见 {@code UserShelfMapper#addIfAbsent}）——
     * 再点一下「加入书架」不该把这本书顶到书架最上面。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
