package com.novel.modules.interaction.vo;

import com.novel.modules.content.vo.ChapterBriefVO;
import com.novel.modules.content.vo.NovelBriefVO;
import com.novel.modules.interaction.entity.ReadHistory;
import com.novel.modules.interaction.entity.UserShelf;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的书架」列表项：一本书 + 我的阅读进度。
 *
 * <p><b>★ 这个 VO 是三个来源拼出来的，改字段前先看清哪个字段来自哪：</b>
 * <pre>
 *   user_shelf   → novelId, createTime（加入时间）
 *   novel        → title, cover, authorName, categoryName, chapterCount, ...
 *   read_history → readChapterId/No/Title, progress
 * </pre>
 * 后两个来源都是「按 id 批量取」的（{@code NovelService#briefsOf} /
 * {@code ChapterService#briefsOf}），不是每行查一次。见 {@code ShelfServiceImpl}。
 *
 * <p><b>★★ {@code title} 为 {@code null} 是合法状态，前端必须接住。</b>
 * 书架里那本书被作者下架、或被管理员驳回时，{@code NovelService#briefsOf}
 * 不会返回它（读者侧只见已上架的作品）—— 于是这里除 {@code novelId} /
 * {@code createTime} / 阅读进度外全是 {@code null}，且 {@code available = false}。
 * <p>这种情况<b>不能把整行删掉</b>：读者会以为自己的书架丢了东西，
 * 而真实原因是那本书下架了。正确做法是渲染成「已下架」占位卡，
 * <b>并且仍然提供「移除」按钮</b> —— 服务端的移除接口刻意不校验作品上架，
 * 就是为了让这一行能被清理掉。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "书架项")
public class ShelfItemVO {

    /**
     * ★ 取自 {@code user_shelf.novel_id}，<b>不是</b> {@code NovelBriefVO#getId}。
     * 作品下架后 brief 是 {@code null}，只有这一列还在 ——
     * 而前端的「移除」按钮要拿它拼 URL。
     */
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

    @Schema(description = "已发布章节数", example = "12")
    private Integer chapterCount;

    /** 作品最新章节 id。★ 不是「我读到哪」，那是 {@link #readChapterId} */
    @Schema(description = "最新章节 id", example = "120")
    private Long lastChapterId;

    @Schema(description = "收藏数", example = "87")
    private Integer collectCount;

    /**
     * 作品当前是否可读。
     *
     * <p>★ <b>用一个显式布尔而不是让前端判断 {@code title == null}</b>：
     * 「书名取不到」和「这篇还没加载出来」在前端是<b>同一个形状</b>，
     * 用它当「已下架」的判据，某次改成异步加载后就会出现「已下架」一闪而过。
     * 显式字段没有这个歧义。
     */
    @Schema(description = "作品当前是否可读（false = 已下架 / 已删除）", example = "true")
    private Boolean available;

    /**
     * 我读到的章节 id。
     *
     * <p>★ <b>与 {@link #readChapterNo} / {@link #readChapterTitle} 同生共死</b>：
     * 读过的章节后来被作者撤回时，三个一起为 {@code null}（章节摘要只返回已发布的）。
     * 这时前端要退化成「继续阅读」跳到 {@link #lastChapterId}，
     * <b>不能拿 chapterId 直接跳</b> —— 那个章节读者端已经打不开了（{@code 3001}）。
     */
    @Schema(description = "读到的章节 id；该章已不可见时为 null", example = "120")
    private Long readChapterId;

    @Schema(description = "读到的章节序号", example = "12")
    private Integer readChapterNo;

    @Schema(description = "读到的章节标题", example = "风起")
    private String readChapterTitle;

    /** 阅读进度百分比 0~100。★ 独立于章节那三个字段，章节没了进度仍然有意义 */
    @Schema(description = "阅读进度百分比 0~100", example = "60")
    private Integer progress;

    @Schema(description = "加入书架的时间", example = "2026-09-21 10:00:00")
    private LocalDateTime createTime;

    /**
     * 拼装。四个入参都可为 {@code null}，除了 {@code shelf}。
     *
     * <p>★ 参数多但不抽 Builder：这个对象<b>只有一处构造点</b>
     * （{@code ShelfServiceImpl#myPage}），Builder 只会让那处代码更长。
     */
    public static ShelfItemVO of(UserShelf shelf, NovelBriefVO brief,
                                 ReadHistory history, ChapterBriefVO chapter) {
        ShelfItemVO vo = new ShelfItemVO();
        vo.setNovelId(shelf.getNovelId());
        vo.setCreateTime(shelf.getCreateTime());
        vo.setAvailable(brief != null);
        if (brief != null) {
            vo.setTitle(brief.getTitle());
            vo.setCover(brief.getCover());
            vo.setAuthorName(brief.getAuthorName());
            vo.setCategoryName(brief.getCategoryName());
            vo.setChapterCount(brief.getChapterCount());
            vo.setLastChapterId(brief.getLastChapterId());
            vo.setCollectCount(brief.getCollectCount());
        }
        if (history != null) {
            vo.setProgress(history.getProgress());
            if (chapter != null) {
                vo.setReadChapterId(chapter.getId());
                vo.setReadChapterNo(chapter.getChapterNo());
                vo.setReadChapterTitle(chapter.getTitle());
            }
        }
        return vo;
    }
}
