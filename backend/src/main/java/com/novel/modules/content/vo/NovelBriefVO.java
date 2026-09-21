package com.novel.modules.content.vo;

import com.novel.modules.content.entity.Novel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作品<b>摘要</b>出参：只够渲染一张「书卡」的最小字段集。
 *
 * <p><b>★ 为什么不复用 {@link NovelVO}：</b>{@code NovelVO} 是给「作品列表页」的，
 * 它带着 {@code status} / {@code viewCount} / {@code score} / {@code wordCount} /
 * {@code isFinish} —— 这些在列表页有筛选和排序的意义。而本 VO 的调用方
 * （书架的「我的书架」、阅读历史的列表）手里只有<b>一串 novelId</b>，
 * 它们要的是「这几本书长什么样」，一个筛选项都用不上。
 *
 * <p>这不是「少传几个字段省流量」的取舍 —— 是<b>语义</b>的取舍：
 * 复用 {@code NovelVO} 会让「书架接口为什么返回 viewCount」这个问题永远没有答案，
 * 而下一个想加字段的人会照着它的样子继续加。同 {@code ReadHistoryVO}
 * 拒绝为将来的页面预埋字段的理由（那条写在 {@code ReadHistoryVO} 的类注释里）。
 *
 * <p>★ <b>{@code title} / {@code cover} 可能是 {@code null}，调用方必须能接住。</b>
 * 取数逻辑见 {@code NovelService#briefsOf} —— 未上架（或被逻辑删除）的作品
 * <b>不会被返回</b>，调用方在 map 里查不到就是 {@code null}。
 * 「书架上有一本已下架的书」是真实会发生的：作者下架、管理员驳回，
 * 而读者的书架行还在。这一行必须显示成「已下架」而不是从列表里消失
 * （消失会让读者以为自己的书架丢了东西），所以调用方要留一个占位分支。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作品摘要（书架 / 阅读历史用）")
public class NovelBriefVO {

    @Schema(description = "作品 id", example = "1")
    private Long id;

    @Schema(description = "书名；作品不可见时为 null", example = "测试书")
    private String title;

    @Schema(description = "封面 URL；作品不可见时为 null")
    private String cover;

    /** 作者昵称，来源同 {@code NovelVO#authorName}（不是 {@code novel} 表的列） */
    @Schema(description = "作者昵称", example = "测试作者")
    private String authorName;

    @Schema(description = "分类名", example = "玄幻")
    private String categoryName;

    /** 已发布章节数（{@code novel.chapter_count} 冗余列，由 {@code syncNovelStat} 重算） */
    @Schema(description = "已发布章节数", example = "12")
    private Integer chapterCount;

    /**
     * 最新章节 id。
     *
     * <p>★ <b>这不是「我读到第几章」</b> —— 那是阅读进度，在
     * {@code read_history} 里，由调用方自己拼。这里给的是作品的「最新一章」。
     * 两个东西长得像但毫无关系，混用会做出「继续阅读」跳到<b>最新</b>章
     * 而不是<b>上次</b>章的 bug。
     */
    @Schema(description = "最新章节 id", example = "120")
    private Long lastChapterId;

    /**
     * 收藏数（{@code novel.collect_count} 冗余列，由 {@code refreshCollectCount} 重算）。
     *
     * <p>★ 它和 {@code viewCount} 不一样，是<b>会随读者操作实时变</b>的 ——
     * 所以书架页展示的必须是取数那一刻的值，而不是前端从某处缓存下来的。
     */
    @Schema(description = "收藏数", example = "87")
    private Integer collectCount;

    @Schema(description = "最后更新时间", example = "2026-09-21 10:00:00")
    private LocalDateTime lastUpdateTime;

    /**
     * 组装。分类名 / 作者昵称由调用方批量查好后传进来 ——
     * <b>本类不做任何查库</b>，理由同 {@code NovelVO#of}。
     */
    public static NovelBriefVO of(Novel novel, String categoryName, String authorName) {
        NovelBriefVO vo = new NovelBriefVO();
        vo.setId(novel.getId());
        vo.setTitle(novel.getTitle());
        vo.setCover(novel.getCover());
        vo.setCategoryName(categoryName);
        vo.setAuthorName(authorName);
        vo.setChapterCount(novel.getChapterCount());
        vo.setLastChapterId(novel.getLastChapterId());
        vo.setCollectCount(novel.getCollectCount());
        vo.setLastUpdateTime(novel.getLastUpdateTime());
        return vo;
    }
}
