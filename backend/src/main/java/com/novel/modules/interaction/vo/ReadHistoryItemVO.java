package com.novel.modules.interaction.vo;

import com.novel.modules.content.vo.ChapterBriefVO;
import com.novel.modules.content.vo.NovelBriefVO;
import com.novel.modules.interaction.entity.ReadHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「阅读历史」列表项：一本书 + 我读到哪 + 什么时候读的。
 *
 * <p><b>★★ 为什么不复用 {@link ReadHistoryVO}：</b>那个 VO 是阅读器在用的，
 * 它的类注释里已经逐字写明了这件事 ——
 * 「P6 要做『阅读历史列表』时，那一页确实是只拿一组 novelId 去渲染的，
 * 到时再给那个场景<b>单开一个 VO</b>（带书名/封面/章名）——
 * <b>不要为了将来的那个页面，让现在的阅读器多背三个用不上的字段</b>。」
 * <p>阅读器每次上报进度都会调 {@code get()}，多传的三个字段是每本书
 * 一次查询的开销；而这个列表页一次才查一页。两者的成本结构完全不同。
 *
 * <p>★ <b>{@code available} / 章节字段的消失规则和 {@code ShelfItemVO} 完全一致</b>
 * （作品下架 → 书名封面消失；章节被撤回 → 章节号标题消失），
 * 理由见那个类的注释，不再重复。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "阅读历史项")
public class ReadHistoryItemVO {

    /** ★ 取自 {@code read_history.novel_id}：作品下架后 brief 是 null，只有这一列还在 */
    @Schema(description = "作品 id", example = "1")
    private Long novelId;

    @Schema(description = "书名；作品已下架时为 null", example = "测试书")
    private String title;

    @Schema(description = "封面 URL；作品已下架时为 null")
    private String cover;

    @Schema(description = "作者昵称", example = "测试作者")
    private String authorName;

    @Schema(description = "分类名", example = "玄幻")
    private String categoryName;

    @Schema(description = "作品当前是否可读（false = 已下架 / 已删除）", example = "true")
    private Boolean available;

    @Schema(description = "读到的章节 id；该章已不可见时为 null", example = "120")
    private Long chapterId;

    @Schema(description = "读到的章节序号", example = "12")
    private Integer chapterNo;

    @Schema(description = "读到的章节标题", example = "风起")
    private String chapterTitle;

    @Schema(description = "阅读进度百分比 0~100", example = "60")
    private Integer progress;

    /**
     * 最后一次阅读的时间。
     *
     * <p>★ 列表就是按它倒序排的，而且和 {@code user_shelf.createTime} 不同：
     * 这个值<b>每次上报都会被推前</b>（重读同一章也会），
     * 所以它真的是「最近读这本书是什么时候」。
     */
    @Schema(description = "最后阅读时间", example = "2026-09-21 10:00:00")
    private LocalDateTime updateTime;

    public static ReadHistoryItemVO of(ReadHistory history, NovelBriefVO brief, ChapterBriefVO chapter) {
        ReadHistoryItemVO vo = new ReadHistoryItemVO();
        vo.setNovelId(history.getNovelId());
        vo.setProgress(history.getProgress());
        vo.setUpdateTime(history.getUpdateTime());
        vo.setAvailable(brief != null);
        if (brief != null) {
            vo.setTitle(brief.getTitle());
            vo.setCover(brief.getCover());
            vo.setAuthorName(brief.getAuthorName());
            vo.setCategoryName(brief.getCategoryName());
        }
        if (chapter != null) {
            vo.setChapterId(chapter.getId());
            vo.setChapterNo(chapter.getChapterNo());
            vo.setChapterTitle(chapter.getTitle());
        }
        return vo;
    }
}
