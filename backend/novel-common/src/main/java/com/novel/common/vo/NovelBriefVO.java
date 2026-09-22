package com.novel.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作品<b>摘要</b>出参：只够渲染一张「书卡」的最小字段集。
 *
 * <h3>★★ M2：这个类为什么从 modules/content 搬到了 novel-common</h3>
 *
 * <p>它原来住在 {@code com.novel.modules.content.vo}，只有 content 用。
 * M2 把 interaction 摘成独立服务之后，<b>interaction 也要用它了</b> ——
 * 书架的「我的书架」和阅读历史的列表都要渲染书卡。
 *
 * <p>两条路摆在面前：
 * <ol>
 *   <li>interaction 也 import content 的 VO —— 那就等于 interaction
 *       <b>依赖 content 的内部包</b>，两个服务的类库纠缠在一起，
 *       以后给这个 VO 加一个字段都要担心对面。</li>
 *   <li>搬进 novel-common 共用一份 —— 两边用同一个类型，
 *       <b>Feign 序列化 / 反序列化天然对得上</b>。</li>
 * </ol>
 * 选了第二条。判断依据很直接：<b>这个类型是跨服务契约的一部分</b>
 * （它要在 HTTP 上传输），而契约类型住在 common 是本项目一贯的做法
 * （同 {@code com.novel.common.internal} 下的那几个 DTO）。
 *
 * <p><b>★★ 但静态工厂 {@code of(...)} 的签名【被改过】，这不是笔误：</b>
 * 搬过来之前它是 {@code of(Novel novel, String categoryName, String authorName)} ——
 * 收的是 content 的<b>表实体</b>。而 {@code Novel} 必须留在 content
 * （它带 {@code @TableName} 和逻辑删除字段，是那张表的形状）。
 * 如果把它一起搬进 common，common 就要依赖 content —— 方向就反了。
 * <p>所以改成收<b>展开的字段值</b>：调用方（content）从实体上取下来传进来。
 * ★ 字段清单仍然只有<b>这一处</b>知道 —— 没有退化成「每个调用方各自 set 一遍」，
 * 那才是这次搬家最容易造成的损失。
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
     *
     * <p>★★ <b>M3 起这个「实时」要打个折</b>：写回从同步 Feign 变成了
     * {@code NOVEL_STAT} 消息（interaction → content，见 {@code NovelStatProducer}），
     * 落库延迟通常在百毫秒级 —— 快到用户看不出来，但<b>它不再是同一个事务里的事</b>。
     * ★ 真正受影响的是 {@code ShelfStatusVO}：它那个 collectCount 取自
     * interaction 本地的 {@code COUNT(*)}（同步、当场算），
     * 而本字段读的是 content 库里的冗余列（异步、可能落后一步）。
     * <b>同一个「收藏数」在两个 VO 里新鲜度不同，这是刻意保留的，不是不一致。</b>
     */
    @Schema(description = "收藏数", example = "87")
    private Integer collectCount;

    @Schema(description = "最后更新时间", example = "2026-09-21 10:00:00")
    private LocalDateTime lastUpdateTime;

    /**
     * 组装。分类名 / 作者昵称由调用方批量查好后传进来 ——
     * <b>本类不做任何查库</b>，理由同 {@code NovelVO#of}。
     *
     * <p>★ 参数是<b>展开的字段</b>而不是 {@code Novel} 实体，见类注释
     * 「静态工厂的签名被改过」。调用方在 content 那边，
     * 它手里才有实体。
     */
    public static NovelBriefVO of(Long id, String title, String cover,
                                  String categoryName, String authorName,
                                  Integer chapterCount, Long lastChapterId,
                                  Integer collectCount, LocalDateTime lastUpdateTime) {
        NovelBriefVO vo = new NovelBriefVO();
        vo.setId(id);
        vo.setTitle(title);
        vo.setCover(cover);
        vo.setCategoryName(categoryName);
        vo.setAuthorName(authorName);
        vo.setChapterCount(chapterCount);
        vo.setLastChapterId(lastChapterId);
        vo.setCollectCount(collectCount);
        vo.setLastUpdateTime(lastUpdateTime);
        return vo;
    }
}
