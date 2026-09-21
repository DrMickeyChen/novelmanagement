package com.novel.modules.interaction.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.novel.common.enums.CommentStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论，对应 {@code novel_comment}。
 *
 * <p><b>★ 这张表归 {@code modules/interaction}，不归 {@code modules/content}。</b>
 * 包结构是按二期要拆的服务划的（{@code modules/interaction} ↔ 二期
 * {@code novel-interaction}），而评论是那个服务的核心表 ——
 * {@code ReaderHistoryController} 已经在这个包里了，不要另开一个包。
 * <p><b>这是一条会持续被挑战的决定</b>（「评论挂在作品下面，放 content 不是更顺吗」），
 * 所以把判据说清楚：二期 M2 先摘 content、M3 才摘 interaction。
 * 评论要是放进 content，M3 就没有东西可摘了，而「阅读量上报改走 MQ」
 * 这个里程碑正是建立在「互动是一个独立服务」这个前提上的。
 *
 * <p><b>★★ 这个表【没有 {@code update_time} 列】，所以下面没有那个字段。</b>
 * 别照着别的实体「顺手补上」：{@code MybatisMetaObjectHandler} 是无差别填充的
 * （它只看字段上有没有 {@code @TableField(fill = ...)}，不知道表里有没有那一列），
 * 加上字段之后 MP 生成的 UPDATE 会带上 {@code update_time}，
 * 报 {@code Unknown column 'update_time' in 'field list'} ——
 * 而报错的时机是「管理员点通过的那一刻」，不是启动时。
 * <p>同样的坑在 {@code AuditLog}（没有 {@code update_time}）和
 * {@code ReadHistory}（既没有 {@code update_time} 也没有 {@code deleted}）上各踩过一次。
 *
 * <p><b>★ {@code status} 用 {@link CommentStatus}，不要用 {@code AuditStatus}。</b>
 * 后者是 0待审/1通过/2驳回，看起来一模一样，但它是给 {@code author_apply.status}
 * 用的另一套枚举；写混了不会报错（码值正好重合），只是从此
 * 「评论的状态」和「申请的」由一个枚举同时表示，改一处影响两处。
 *
 * <p><b>★ 两张表规则相反，这是本模块最容易搞混的一处：</b>
 * {@code novel_comment} <b>有</b> {@code deleted}（用户自己删的，逻辑删），
 * 但<b>没有</b> {@code update_time}；而 {@code sys_user_role} / {@code sys_role_menu}
 * 是反过来 —— 没有 {@code deleted}。所以手写 SQL 时，
 * 「要不要补 {@code deleted = 0}」必须先看是哪张表，不能靠记忆。
 *
 * @author MickeyChen
 */
@Data
@TableName("novel_comment")
public class NovelComment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@link #targetType} 的书评取值：{@code targetId} 是 {@code novel_id} */
    public static final int TARGET_TYPE_NOVEL = 1;

    /** {@link #targetType} 的章评取值：{@code targetId} 是 {@code chapter_id} */
    public static final int TARGET_TYPE_CHAPTER = 2;

    /**
     * {@link #targetType} 的中文文案：{@code 1 → 书评}、{@code 2 → 章评}。
     *
     * <p>★ <b>文案由后端给、颜色由前端给</b>，和 {@code CommentVO#statusDesc} 是同一条规矩
     * （{@code admin-web/src/utils/status.js} 的类注释第一句就写明了）。
     * 前端拿 {@code targetType} 的<b>数字</b>去取颜色，不拿这个字符串做判断。
     *
     * <p>★ 未知值返回 {@code null} 而不是抛异常：这是个展示字段，
     * 为一个「库里出现了 3」的情况把整个评论列表打成 500，代价远大于
     * 那一行的类型列空着。（对照 {@code CommentStatus#fromCode} 的抛异常 ——
     * 那个值决定的是<b>状态流转</b>，走错了会写坏数据。）
     */
    public static String targetTypeDesc(Integer targetType) {
        if (targetType == null) {
            return null;
        }
        return switch (targetType) {
            case TARGET_TYPE_NOVEL -> "书评";
            case TARGET_TYPE_CHAPTER -> "章评";
            default -> null;
        };
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评论人 {@code sys_user.id}。★ 删除时的归属校验就是拿它和当前登录用户比 */
    private Long userId;

    /**
     * 评论对象类型：<b>1 书评（{@code target_id} 是 novel_id）、2 章评（是 chapter_id）</b>。
     *
     * <p><b>★ 这里刻意用 {@code Integer}，没有建 {@code CommentTargetType} 枚举。</b>
     * 本来按本项目的习惯（{@code NovelStatus} / {@code CommentStatus} /
     * {@code AuditTargetType} 全是枚举）应该建一个，但那个枚举会长得和
     * {@code common/enums/AuditTargetType} <b>几乎一样</b>（都含 NOVEL / CHAPTER），
     * 而「两个近义枚举同时存在」正是最难发现的一类混淆 ——
     * 参照 {@code sys_role} / {@code sys_role_menu} / {@code sys_menu} 三张表的教训。
     * <p>P5 只会写 {@code 1}（书评）；P6 开始写 {@code 2}（章评）。
     * <b>★ P6 复查过，结论是仍然不升级成枚举</b> —— 理由同上，两个近义枚举
     * 比一个常量更难查。但两个常量从 {@code CommentServiceImpl} <b>搬到了本类</b>：
     * 因为 {@code CommentVO} 也要按它出中文文案，常量留在实现类里会让 VO
     * 反向依赖 Service 实现。
     */
    private Integer targetType;

    /** 评论对象的 id（书评是 novel_id，章评是 chapter_id）。和 targetType 一起定位，没有外键 */
    private Long targetId;

    /**
     * 二级回复的父评论 id，一级评论为 {@code null}。
     *
     * <p>★ <b>P5 没有任何接口能写入它</b>（回复是 P6 的），所以它会一直是 null，
     * 而全局配了 {@code default-property-inclusion: non_null}，
     * 于是它在 JSON 里【整个不存在】。这是正常的，前端取值写 {@code ?? null} 即可。
     * <p>字段留着而不是删掉，是因为表上有这一列、也有 {@code idx_parent_id} 索引 ——
     * 实体少一个字段不会报错，只会让「查询按 parent_id 分组」这类代码
     * 在 P6 想写的时候发现字段不在。
     */
    private Long parentId;

    /** 评论正文，最长 {@code CommonConstant.COMMENT_MAX_LENGTH} 个字 */
    private String content;

    /**
     * 评分，<b>仅书评有</b>，1~5 分；不评分则为 {@code null}。
     *
     * <p>★ 可以为空是有意的（列定义就是 {@code NULL}）：把「打分」变成发表评论的
     * 必填项，会让只想说两句话的读者被迫随便点一个分数，
     * 而那些凑数的分数会直接拉低 {@code novel.score}（P6 才维护那个字段）。
     */
    private Integer score;

    /**
     * 点赞数。★ <b>由 {@code CommentServiceImpl#like} 用一条
     * {@code like_count = like_count + 1} 原子地维护</b>，不要「查出来加一再写回去」。
     *
     * <p>防重复点赞的依据不在这一列，也不在库里 —— 全库 22 张表<b>没有
     * {@code comment_like} 表</b>（已核实），所以用 Redis 的 Set 挡
     * （{@code RedisKeyConstant.LIKE_USER}）。见 {@code CommentServiceImpl#like} 的
     * 长注释：那里写清了「Redis 被清空之后会怎样」。
     */
    private Integer likeCount;

    /** 审核状态：0待审 1已显示 2已隐藏。★ 用 {@link CommentStatus}，不是 AuditStatus（见类注释） */
    private CommentStatus status;

    /** 审核人 {@code sys_user.id}，未审核时为 null */
    private Long auditUserId;

    /** 审核时间，未审核时为 null */
    private LocalDateTime auditTime;

    /**
     * 审核备注。驳回 / 隐藏时是理由。
     *
     * <p>★ <b>P5 不在读者端展示它</b> —— 读者看不到「你的评论被隐藏了，
     * 原因是 XX」这种反馈（P5 的读者端只有公告横幅，见决定 11）。
     * 所以这个字段现在是<b>给管理员自己看的</b>：同一批违规评论连续处理时，
     * 它是「上次为什么隐藏这一条」的唯一记录。
     * <p>不要因为「反正读者看不到」就不填 —— 一条没有理由的隐藏记录，
     * 半年后复查时等于没有记录。
     */
    private String auditRemark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 逻辑删除。<b>★ 它和 {@link #status} 是两个维度，不是一回事：</b>
     * <pre>
     *   status = 2 已隐藏 —— 【管理员】的操作，内容违规
     *   deleted = 1      —— 【用户自己】删的，或者管理员清理
     * </pre>
     * 所以 {@link CommentStatus} 里<b>没有 DELETED</b>（那个枚举的注释也写了这一点）。
     * 合并成一个字段的话，「读者自己删掉的评论」和「被管理员隐藏的评论」
     * 在库里就分不出来了 —— 而这两件事在统计上正好要分开
     * （前者说明读者反悔，后者说明内容有问题）。
     *
     * <p>★ <b>刻意没有 {@code @TableField(select = false)}</b>
     * —— 和 {@code Novel} 的 {@code deleted} 不同。因为管理端的评论列表
     * <b>要显示「已删除」这个标记</b>（那条查询是手写 SQL，见
     * {@code NovelCommentMapper#selectAdminPage}），把这一列 select 掉的话
     * 它就永远是 null，界面上那些被删掉的评论会看起来完全正常。
     */
    @TableLogic
    private Integer deleted;
}
