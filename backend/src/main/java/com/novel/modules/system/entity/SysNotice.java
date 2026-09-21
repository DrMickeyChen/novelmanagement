package com.novel.modules.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内公告，对应 {@code sys_notice}。管理员发布，读者端首页横幅展示。
 *
 * <p><b>★ 这张表归 {@code modules/system}，但它【不是】审核对象。</b>
 * 全项目的 {@code audit_log} 只记四种东西的<b>状态流转</b>：
 * 作品、章节、评论、作者申请（{@code AuditTargetType} 那四项）。
 * 公告是<b>运营内容</b>，不是「有人提交了一个东西、需要别人裁决」——
 * 它没有申请人、没有待审态、没有驳回理由，管理员发布它就是它自己的终态。
 * <p>所以本模块<b>一行 {@code auditRecorder.record(...)} 都不会有</b>，
 * 这不是漏了：给公告补一条流水，只会让「审核流水」页上出现一类
 * 既没有 target 也没有审核人的记录，把「处置了几条违规」这个统计口径搅浑。
 *
 * <p><b>★★ {@code status} 和 {@code type} 都用 {@code Integer}，不建枚举。</b>
 * 两个字段的判据不同，但结论一样：
 * <ul>
 *   <li>{@code status}（1发布 0下线）是个<b>开关</b>，不是状态机 ——
 *       没有 {@code canTransitTo}，没有终态，来回切都合法。
 *       项目里 {@code sys_user.status} / {@code novel_category.status} /
 *       {@code sys_menu.status} 全是 {@code Integer}，这里跟它们一队。</li>
 *   <li>{@code type}（1公告 2通知）<b>只影响前端怎么显示</b>（图标/颜色），
 *       后端在任何地方都不按它分支。给一个「谁都不读」的字段建枚举，
 *       等于给未来的自己一个「这个枚举很重要」的错觉 ——
 *       {@code sys_menu.type} 也是同样的处境，也是同样的处理。</li>
 * </ul>
 * <b>对照：</b>{@code AuditStatus} / {@code CommentStatus} 是枚举，
 * 因为它们真的有流转规则，而且后端要按码值做判断。
 *
 * <p><b>★ 这一张表【没有】{@code version} 列</b>，所以并发控制只能靠
 * 「前置状态」或干脆不做。公告选择了不做乐观检查 —— 理由见
 * {@code NoticeServiceImpl} 的类注释（两个管理员同时改一条公告的后果
 * 只是「后写的赢」，不存在半写状态）。
 *
 * @author MickeyChen
 */
@Data
@TableName("sys_notice")
public class SysNotice implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标题，最长 200 字。★ 读者端的横幅上显示的就是它（正文要点开才看得到） */
    private String title;

    /** 正文，{@code TEXT}。★ 库的容量是 64KB，但接口层卡在 5000 字，见 {@code NoticeSaveDTO} */
    private String content;

    /** 类型：1公告 2通知。★ 只影响前端显示，见类注释 */
    private Integer type;

    /**
     * 状态：1发布 0下线。
     *
     * <p><b>★★ 它和 {@link #publishTime} 是一对，规则只有一条：
     * {@code status = 1} 当且仅当 {@code publishTime} 非空。</b>
     * <pre>
     *   新建为草稿（0）        → publish_time 保持 NULL
     *   新建即发布（1）        → 盖当前时间
     *   草稿 → 发布            → 盖当前时间
     *   发布 → 下线            → 【保留】原时间，不清空
     *   下线 → 再发布          → 【重新】盖当前时间
     *   编辑但不动 status      → 【不动】原时间
     * </pre>
     * 四条边界各有后果，全部由 {@code NoticeServiceImpl#resolvePublishTime}
     * 一处负责，理由写在那个方法上。
     *
     * <p>★ <b>{@code publishTime} 不由前端传</b> ——
     * {@code NoticeSaveDTO} 里根本没有这个字段。放它进 DTO 就等于允许
     * 出现「status = 1 但时间戳是空」和「公开时间在未来」这两种状态，
     * 而前者会让读者端的排序（按 publish_time 倒序）出现 NULL，
     * 后者会让公告「提前」出现在本该还没发布的时候。
     */
    private Integer status;

    /**
     * 发布时间。★ 读者端列表按它倒序，所以它同时是「什么时候发的」和「排序键」。
     *
     * <p><b>★ 它【不是】{@code create_time} 的别名，两者在草稿上就分开了：</b>
     * 一条 3 月 1 日建、3 月 20 日才发出去的公告，
     * {@code create_time = 3/1} 而 {@code publish_time = 3/20}。
     * 读者看到的「最新公告」应该按后者算。
     */
    private LocalDateTime publishTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除。★ 管理端有删除接口（对照 {@code AuthorApply} 那张表根本没有删除路径） */
    @TableLogic
    private Integer deleted;
}
