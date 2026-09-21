package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 编辑公告的入参。两个接口共用 —— 字段完全一样，没必要拆成两份。
 *
 * <p><b>★★ 这里【没有】{@code publishTime}，这是本类最重要的一件事。</b>
 * 发布时间由后端根据 {@code status} 的<b>变化</b>来决定
 * （见 {@code SysNotice#status} 里那张规则表），前端一个字节都碰不到它。
 * 放它进来就等于允许出现这两种状态：
 * <pre>
 *   ① status = 1 但 publishTime = null
 *      → 读者端按 publish_time DESC 排序时出现 NULL，
 *        MySQL 里 NULL 在倒序中排在【最后】，于是这条公告永远沉底，
 *        而且它「明明是已发布的」。前端翻遍接口也找不出为什么它不出现。
 *   ② publishTime 在未来
 *      → 公告「提前」出现在本不该出现的时候，或者反过来，
 *        运营以为设了定时发布，其实后端根本没做定时任务。
 * </pre>
 * ★ 一句话：<b>能由状态推出来的字段，就不要让调用方传。</b>
 *
 * <p><b>★ {@code type} / {@code status} 既给了默认值、又挂了 {@code @NotNull}，
 * 两件事缺一不可：</b>
 * <pre>
 *   默认值（字段初始化）—— 「JSON 里没这个 key」时用。Jackson 不会去碰
 *                          没出现的字段，于是 Service 拿到的一定不是 null。
 *   @NotNull          —— 「JSON 里有这个 key、但值是 null」时用。
 *                          显式 null 会【覆盖掉】那个默认值。
 * </pre>
 * <p><b>★★ 为什么公告必须补这个 {@code @NotNull}，而 {@code CategorySaveDTO}
 * 没有补也相安无事：</b>分类只有「null 那一列不被更新」这一个后果
 * （MP 的 {@code insert} / {@code updateById} 都会跳过 null 字段，
 * 落库时由建表语句的 {@code DEFAULT} 兜住），结果是对的。
 * <p>而公告的 {@code status} 上挂着一条<b>跨列不变式</b>：
 * {@code status = 1} 当且仅当 {@code publishTime} 非空。
 * 显式传 null 时 {@code status} 列被 MP 跳过、由 {@code DEFAULT 1} 变成 1，
 * 而 Service 判的是 {@code isPublished(null) == false}、<b>不盖时间戳</b> ——
 * 于是库里出现一条「已发布但 publish_time 是 NULL」的公告。
 * 它在读者端按 {@code publish_time DESC} 排序时排在<b>最后</b>，
 * 一条刚发的公告沉在列表底部，而两个界面看起来都正常。
 * <p>这是一个真实存在的坏状态，所以堵在入口：<b>要么不传（默认 1），
 * 要么传 0/1，传 null 直接 400。</b>
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "公告新增 / 编辑入参")
public class NoticeSaveDTO {

    /**
     * 标题。
     *
     * <p><b>★ 没有「标题不能重复」的校验，这是有意的。</b>
     * 「系统维护通知」这种标题一年要发好几次，重复是正常的 ——
     * 给它加唯一键只能逼着运营在标题后面缀日期（「系统维护通知（9月）」），
     * 而那串日期对读者没有任何意义。
     * <p>对照 {@code CategorySaveDTO}：分类名<b>有</b>唯一键，因为
     * 「玄幻」这个分类只能有一个，两个同名的分类会让作品挂到哪一个都说不清。
     * <b>判据是「重名会不会产生歧义」，不是「是不是名字」。</b>
     */
    @Schema(description = "公告标题", example = "系统维护通知", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 200, message = "公告标题最多 200 个字")
    private String title;

    /**
     * 正文。
     *
     * <p><b>★★ {@code max = 5000} 比库的容量更严，这是刻意的。</b>
     * 列类型是 {@code TEXT}，理论上能放 64KB（utf8mb4 下约 1.6 万个汉字）——
     * 但那个上限对「一条公告」来说是荒谬的。一条超过 5000 字的公告，
     * 要么该发成文章，要么该拆成几条。
     * <p>收紧在这一层的意义是<b>报错的位置</b>：超了给 HTTP 400
     * 「公告内容最多 5000 个字」，而不是让 MySQL 抛
     * {@code Data too long for column 'content'} 变成 500。
     * <p>★ 注意 {@code @Size} 数的是<b>字符</b>，MySQL 限的是<b>字节</b>，
     * 两者对中文的比例是 1:3。这里有意不去精确对齐字节数 ——
     * 5000 字（约 15KB）离 TEXT 的 64KB 还有四倍余量，够安全，
     * 而精确对齐需要自定义校验器，收益为零。
     */
    @Schema(description = "公告正文", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "公告内容不能为空")
    @Size(max = 5000, message = "公告内容最多 5000 个字")
    private String content;

    /**
     * 类型：1 公告，2 通知。
     *
     * <p>★ 后端不按它分支，只影响前端显示（图标 / 颜色）。理由见
     * {@code SysNotice} 的类注释。
     * <p>★ {@code @Min/@Max} 把范围卡死在 1~2：传个 7 进去如果只按
     * 「非 1 即 2」处理，会静默存下 7，以后所有 {@code type = 2} 的判断
     * 都会漏掉这一行（同一段理由见 {@code CategorySaveDTO#status}）。
     */
    @Schema(description = "类型：1 公告，2 通知", example = "1", defaultValue = "1")
    @NotNull(message = "公告类型不能为空，可选 1（公告）或 2（通知）")
    @Min(value = 1, message = "公告类型只能是 1（公告）或 2（通知）")
    @Max(value = 2, message = "公告类型只能是 1（公告）或 2（通知）")
    private Integer type = 1;

    /**
     * 状态：1 发布，0 下线。
     *
     * <p><b>★ 发布和下线是同一个接口的两个取值，没有单独的「发布」按钮接口。</b>
     * 前端的「发布 / 下线」行内按钮走的就是这次 PUT（把 status 翻一下，
     * 其余字段原样带上）—— 和分类管理页的「启用 / 停用」完全同一套做法。
     * <p>★ 这与作品/章节/申请的审核<b>刻意不同</b>：那些有状态机，
     * 需要单独的动作语义（通过 / 驳回 + 理由），所以各有 {@code /audit} 接口。
     * 公告没有状态机，多开一个 {@code /publish} 接口只是把一次 PUT 拆成两个入口。
     * <p><b>★ 下线【不会】清空 {@code publishTime}</b>，再发布时会盖新时间 ——
     * 见 {@code SysNotice#status}。
     *
     * <p><b>★★ {@code @NotNull} 在这里不是形式主义</b>，它堵的是一个真实存在的
     * 坏状态（「已发布但 publish_time 为 NULL」）。完整推理在类注释里。
     */
    @Schema(description = "状态：1 发布，0 下线", example = "1", defaultValue = "1")
    @NotNull(message = "状态不能为空，可选 1（发布）或 0（下线）")
    @Min(value = 0, message = "状态只能是 0（下线）或 1（发布）")
    @Max(value = 1, message = "状态只能是 0（下线）或 1（发布）")
    private Integer status = 1;
}
