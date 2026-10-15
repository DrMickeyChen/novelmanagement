package com.novel.modules.content.vo;

import com.novel.modules.content.entity.Chapter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 章节<b>详情</b>出参 —— 在 {@link ChapterVO} 的基础上多出「正文」和「驳回理由」。
 *
 * <p><b>★ 这是全项目唯一会返回 {@code content}（正文）的 VO。</b>
 * 它只服务三个「单个章节」的接口，每一个都有明确理由：
 * <ul>
 *   <li>{@code GET /api/author/chapters/{id}} —— <b>作者重新打开编辑器</b>。
 *       没有它，作者关掉浏览器就再也读不回自己写的正文，等于章节写完不能改。</li>
 *   <li>{@code GET /api/admin/chapters/{id}} —— 大纲 §889 的
 *       「弹窗预览正文后通过 / 驳回」。审核员<b>必须能看到正文才能审</b>，
 *       只看标题点通过等于没审。</li>
 *   <li>{@code GET /api/novels/{id}/chapters/{cid}} —— <b>阅读器的核心接口</b>，
 *       读者就是来读正文的。</li>
 * </ul>
 * <b>任何列表接口都不许用这个 VO</b>，包括「章节列表」——
 * 那是 {@link ChapterVO}。一个 100 章的列表带上正文就是几百 MB。
 *
 * <p>继承写法（{@code fillFrom} + {@code callSuper}）的理由与
 * {@link NovelDetailVO} 完全相同，见那边的类注释。
 *
 * @author MickeyChen
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "章节详情（含正文）")
public class ChapterDetailVO extends ChapterVO {

    private Long contentVersion;

    /**
     * 正文。
     *
     * <p><b>★ 可能是 {@code null} —— 这不代表「这一章没内容」，代表「这一章还没写正文」。</b>
     * 作者可以先建好章节占位、回头再写（大纲 §629 的「先写大纲后建正文」就是这个意思），
     * 所以建章时正文允许为空。
     *
     * <p>由于全局配了 {@code default-property-inclusion: non_null}，
     * 为 {@code null} 时<b>这个字段在 JSON 里直接不出现</b>，
     * 前端编辑器要写 {@code detail.content ?? ''}，
     * 否则把 {@code undefined} 塞进 {@code <textarea>} 会显示成字符串 "undefined"。
     *
     * <p><b>什么时候会强制要求正文非空：提交审核</b>
     * （{@code POST /api/author/chapters/{id}/submit}，抛
     * {@code CHAPTER_CONTENT_EMPTY}）。草稿阶段不拦 ——
     * 拦了会让编辑器的「全选删除、准备重写」这一步没法保存。
     */
    @Schema(description = "正文。★ 未写正文时为 null 且字段不出现", example = "少年睁开眼，发现自己站在山巅……")
    private String content;

    /**
     * 审核备注 —— 章节的驳回理由，给作者看的。
     *
     * <p><b>★ 读者端必须为 {@code null}：</b>
     * 阅读器接口（{@code GET /api/novels/{id}/chapters/{cid}}）虽然也用这个 VO，
     * 但它查出来的一定是「已发布」的章节，而已发布的章节不会有驳回理由。
     * 即便如此，<b>阅读器那条路径要显式 {@code setAuditRemark(null)}</b>，
     * 或者干脆不复用这个 VO 的转换方法 —— 「数据恰好为空」不是防线。
     */
    @Schema(description = "审核备注（驳回理由）。★ 仅作者端 / 管理端返回", example = "正文含违规内容，请修改后重新提交")
    private String auditRemark;

    /* ==================== 构造 ==================== */

    /**
     * 从实体 + 正文转换。
     *
     * <p>{@code content} 由调用方从 {@code chapter_content} 表查好传进来 ——
     * 正文在另一张表，这个 VO 不查库（同 {@code NovelVO#of} 的理由）。
     *
     * @param content    正文，未写时为 {@code null}
     * @param novelTitle 所属作品书名
     */
    public static ChapterDetailVO of(Chapter entity, String content, String novelTitle) {
        ChapterDetailVO vo = new ChapterDetailVO();
        // ★ 父类那些字段走父类的 fillFrom，这里一个字都不重复
        vo.fillFrom(entity, novelTitle);
        vo.setContentVersion(entity.getContentVersion());
        vo.setContent(content);
        vo.setAuditRemark(entity.getAuditRemark());
        return vo;
    }
}
