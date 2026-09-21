package com.novel.modules.content.vo;

import com.novel.modules.content.entity.Novel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 作品<b>详情</b>出参 —— 在 {@link NovelVO} 的基础上多出「简介 / 标签 / 驳回理由 /
 * 首章 id」这几样只有详情页才需要的东西。
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
 * 子类会跟着丢。但删列表字段会同时影响三个端点的列表页，本来就不该随手删。
 *
 * @author MickeyChen
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "作品详情（在列表项的基础上多了简介、标签、首章 id）")
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
     * 第一章的 id（<b>已发布章节里 <code>chapterNo</code> 最小的那个</b>），
     * 详情页「开始阅读」按钮的跳转目标。<b>这本书一章都没发布时为 {@code null}</b>。
     *
     * <p><b>★★ P6 之前这里是 {@code chapters}（完整目录），现在没有了 —— 这不是漏改。</b>
     * 目录改成独立接口，读者端翻页看目录走 {@code GET /api/novels/{id}/chapters}，
     * 阅读器要整条列表走 {@code GET /api/novels/{id}/catalog}。
     * 理由：几百章的书每打开一次详情都要拖回全部章节，而详情页是匿名高频入口。
     *
     * <p><b>★ 那为什么还要留这一个 id：</b>「开始阅读」原来取 {@code chapters[0]}，
     * 目录分页之后第一页可能一条都没有（这本书的章节全在第二页是不可能的，
     * 但「一章都没发布」是可能的），而更根本的是 —— <b>详情接口不该为了一个按钮
     * 让调用方再发一次目录请求</b>。给一个 id 是一个字段的成本，前端一次请求就够。
     * <p>★ 不能改成在 {@code NovelVO} 上放 {@code chapterCount} 让前端去猜：
     * 那个数由 {@code syncNovelStat} 维护，和「第一章是哪一章」是两件事。
     *
     * <p><b>★ 为什么不放 {@code NovelVO}（列表也带上）：</b>列表页没有「开始阅读」按钮。
     * 列表 20 条就是 20 次「查第一章」，为一个不显示的按钮付这个代价没有道理。
     */
    @Schema(description = "第一章 id。★ 仅读者端返回；一章都没发布时为空", example = "10")
    private Long firstChapterId;

    /**
     * 从实体转换。<b>不填 {@link #firstChapterId}</b> —— 「第一章是哪一章」要查
     * {@code chapter} 表，而 {@code of()} 不查库（同 {@code NovelVO.of} 的理由）。
     *
     * <p><b>★ 三个详情方法里只有 {@code readerDetail} 会填它。</b>
     * 作者端和管理端的详情页都没有「开始阅读」按钮，多查一次是白查。
     * 不填时为 {@code null}，这个 key 在 JSON 里直接消失
     * （{@code default-property-inclusion: non_null}）——
     * 这两个端点的前端本来就不读它，所以不需要和「这本书没章节」区分开。
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
