package com.novel.modules.content.vo;

import com.novel.modules.content.entity.Novel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 作品<b>详情</b>出参 —— 在 {@link NovelVO} 的基础上多出「简介 / 标签 / 驳回理由 /
 * 目录」这几样只有详情页才需要的东西。
 *
 * <p><b>★ 为什么这里用继承，而 {@code NovelCreateDTO} / {@code NovelUpdateDTO} 却是分开的两个类：</b>
 * 两处的取舍正好相反，理由不同，不是标准不一致：
 * <ul>
 *   <li>那两个 DTO 各 6 个字段，<b>抄一遍的成本接近零</b>，而它们的分化是
 *       <b>可预见的</b>（「书名通过审核后不允许再改」只会加在编辑上）。</li>
 *   <li>这两个 VO 有 14 个字段完全重合，抄一遍的代价是
 *       「以后 {@code NovelVO} 加一个字段（比如『是否已收藏』），
 *       详情页忘了跟着加」—— <b>这种漏改在继承下不可能发生</b>。</li>
 * </ul>
 *
 * <p><b>★★ Lombok 在继承上的两个坑，这里的两个注解不是装饰、是修 bug：</b>
 * <pre>
 *   &#64;EqualsAndHashCode(callSuper = true)   // 不加 → 子类的 equals 只比自己的字段，
 *                                          //   父类字段不同的两个对象会被判成「相等」
 *   &#64;ToString(callSuper = true)            // 不加 → 打日志时看不到父类字段，
 *                                          //   排查问题时打印出来是个「没有 id 的详情对象」
 * </pre>
 * Lombok 默认<b>不</b>调用 {@code super}（因为它无法确定父类是不是也用了 Lombok），
 * 只会给一条编译警告 —— 而这条警告淹没在构建输出里，几乎没人看。
 *
 * <p><b>本项目只有两处 VO 用继承，就是这一对和 {@code ChapterDetailVO}：
 * 「列表项 → 详情」这一种关系。</b>两处都遵循同一套写法，照着抄即可：
 * <pre>
 *   父类：protected void fillFrom(实体, ...)   // 父类字段只在这一处赋值
 *   子类：&#64;EqualsAndHashCode(callSuper = true) + &#64;ToString(callSuper = true)
 *         of() 里先调 fillFrom(...)，再 set 自己独有的字段
 * </pre>
 * <b>不要扩散到别处</b> —— 没有这层「详情就是在列表项上多几个字段」的关系时，
 * 继承只会让「哪些字段到底从哪来」变得难查。
 *
 * <p><b>什么时候该把继承拆掉：</b>如果哪天要「给列表 VO 删掉一个字段」，
 * 子类会跟着丢。但删列表字段会同时影响三个端点的列表页，本来就不该随手删 ——
 * 真到那一步，把 {@link #chapters} 之外的东西抽出来另说。
 *
 * @author MickeyChen
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "作品详情（在列表项的基础上多了简介、标签、目录）")
public class NovelDetailVO extends NovelVO {

    /**
     * 简介。
     *
     * <p>★ <b>读者端详情页显示它</b>，所以它必须对读者可见 ——
     * 也就是说简介里不能写任何只给内部看的东西。
     * 作者在作者端填的简介，从这里直接走到读者眼前。
     */
    @Schema(description = "简介", example = "一个少年从山村走出，一步步走向巅峰的故事。")
    private String intro;

    @Schema(description = "标签，逗号分隔", example = "热血,升级流,东方玄幻")
    private String tags;

    /**
     * 审核备注 —— <b>驳回理由是给作者看的</b>（「简介与正文不符，请修改后重新提交」）。
     *
     * <p><b>★ 这个字段只在作者端和管理端返回，读者端必须为 {@code null}。</b>
     * 读者的可见性由 C-7 约束 1（只查已上架作品）保证，已上架的作品
     * 在这个字段上本来就是空的 —— 但「恰好为空」不是防线。
     * 所以读者端的转换<b>走 {@code NovelService#readerDetail}</b>，
     * 它是单独一个方法、不返回这个字段；<b>不要图省事让读者端复用
     * {@code authorDetail} 的结果</b>。
     */
    @Schema(description = "审核备注（驳回理由）。★ 仅作者端 / 管理端返回", example = "简介与正文不符，请修改后重新提交")
    private String auditRemark;

    /**
     * 创建时间。★ 列表页不显示，详情页显示「创建于」。
     *
     * <p>注意它和 {@code lastUpdateTime} 不是一回事：
     * {@code createTime} 是这一行被 INSERT 的时间，
     * {@code lastUpdateTime} 是最新已发布章节的时间。
     * 一本 2025 年建的书 2026 年还在更新，这两个值会差一年，都属于正常。
     */
    @Schema(description = "创建时间", example = "2026-09-20 10:30:00")
    private LocalDateTime createTime;

    /**
     * 章节目录。
     *
     * <p><b>★★ 这个列表的筛选条件随端点而变，这是 C-7 最容易做错的地方：</b>
     * <table border="1">
     *   <tr><th>端点</th><th>包含哪些章节</th></tr>
     *   <tr><td>读者端详情 {@code GET /api/novels/{id}}</td>
     *       <td><b>只有已发布（status = 2）的</b>。草稿章、待审章、被驳回的章
     *           都必须排除 —— 漏一个就是「未审核的内容被读者看到了」。</td></tr>
     *   <tr><td>作者端详情 {@code GET /api/author/novels/{id}}</td>
     *       <td>全部状态（作者要看到自己的草稿）</td></tr>
     *   <tr><td>管理端详情 {@code GET /api/admin/novels/{id}}</td>
     *       <td>全部状态（审核员要看到待审的那些）</td></tr>
     * </table>
     *
     * <p><b>★ 初始化成空列表而不是 {@code null}，是有意的：</b>
     * 全局配了 {@code default-property-inclusion: non_null}，
     * {@code null} 会让这个字段<b>在 JSON 里整个消失</b>，
     * 前端就得写 {@code detail.chapters || []}。给空数组则是
     * {@code "chapters": []}，前端可以直接 {@code v-for}。
     * <p>但这也意味着「忘了填」和「这本书确实没有章节」在返回里长得一样 ——
     * 所以<b>三个详情方法都必须显式调用 {@code setChapters(...)}</b>，
     * 靠空列表兜底会把「漏填目录」这种 bug 藏起来。
     */
    @Schema(description = "章节目录。★ 读者端只含已发布章节")
    private List<ChapterVO> chapters = new ArrayList<>();

    /**
     * 从实体转换。<b>不填 {@link #chapters}</b> —— 目录由调用方按上表的规则查好后
     * 再 {@code setChapters(...)}。
     *
     * <p>为什么不在这里一起查：读者端要过滤状态、作者端不要，
     * 而 {@code of()} 拿不到「当前是谁在问」这个信息。
     * 与其加一个 {@code boolean forReader} 参数（调用方很容易传错，
     * 而传错的后果是内容泄露），不如把这一步显式留在调用处 ——
     * <b>调用处的代码是 {@code readerDetail} / {@code authorDetail}，
     * 名字本身就说明了它该用什么口径</b>。
     */
    public static NovelDetailVO of(Novel entity, String categoryName, String authorName) {
        NovelDetailVO vo = new NovelDetailVO();
        // ★ 父类那 14 个字段走父类的 fillFrom，这里一个字都不重复 ——
        //   以后 NovelVO 加字段，详情自动就有（这正是用继承的意义所在）
        vo.fillFrom(entity, categoryName, authorName);
        // 以下是详情独有的
        vo.setIntro(entity.getIntro());
        vo.setTags(entity.getTags());
        vo.setAuditRemark(entity.getAuditRemark());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
