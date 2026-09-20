package com.novel.modules.content.vo;

import com.novel.common.enums.ChapterStatus;
import com.novel.modules.content.entity.Chapter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 章节<b>元信息</b>出参（目录用）。读者端 / 作者端 / 管理端三处共用。
 *
 * <p><b>★★ 这个类里没有、也不能有 {@code content}（正文），这是本类最重要的一条。</b>
 * 目录一页可能几十上百章，每章正文两三万字 —— 带上正文就是一次请求几十 MB。
 * 而且 {@code chapter} 表在设计上<b>本来就不存正文</b>（正文在 {@code chapter_content}，
 * 拆表的目的正是「查目录时不拖 LONGTEXT」，见大纲 §1343）。
 * 在 VO 上加一个 {@code content} 字段，等于把那张表拆开的努力又还回去了。
 * <p>正文只在 {@link ChapterDetailVO}，而且只有「单个章节」的接口才返回它。
 *
 * <p><b>★ 「章节列表」这个接口返回的是给所有人看的目录吗？不是。</b>
 * 同一个 {@code ChapterVO} 会用在三个地方，包含哪些章节由<b>查询条件</b>决定：
 * <ul>
 *   <li>读者端 {@code GET /api/novels/{id}} 的目录 —— <b>只有已发布</b>（C-7 约束 2）</li>
 *   <li>作者端 {@code GET /api/author/novels/{novelId}/chapters} —— 全部状态</li>
 *   <li>管理端 {@code GET /api/admin/chapters} —— 全部状态，常用 {@code status=1} 筛待审</li>
 * </ul>
 * VO 本身不带口径，<b>口径错了不会报错、只会多返回几行</b>，
 * 所以每个 Service 方法都要在注释里写明自己用的是哪种。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "章节信息（不含正文）")
public class ChapterVO {

    @Schema(description = "章节 id", example = "1")
    private Long id;

    @Schema(description = "所属作品 id", example = "1")
    private Long novelId;

    /**
     * 所属作品的书名。
     *
     * <p><b>为什么章节 VO 要带书名（读者端目录里书名是已知的，看着冗余）：</b>
     * 管理端的章节列表是<b>跨作品</b>的 —— 审核员筛「全部待审章节」时，
     * 列表里混着好几本书的章节，<b>不显示书名就完全不知道该审的是哪本</b>。
     * 而让管理端为每一行再发一个请求查书名，就是前端版的 N+1。
     *
     * <p>所以这个字段是为管理端加的，读者端/作者端拿到它是顺带的（一章 ~10 字节）。
     * 由调用方查好传进来，见 {@link #fillFrom}。
     */
    @Schema(description = "所属作品的书名", example = "测试书")
    private String novelTitle;

    /**
     * 第几章。
     *
     * <p><b>★ 这是「存活章节的序号」，永远是正数。</b>
     * 被删除的章节在库里被改成了 {@code -id}（见
     * {@code ChapterMapper#logicDeleteAndFreeChapterNo}），
     * 但那些行被 {@code deleted = 0} 挡在查询之外，不会出现在这里。
     * 所以前端可以放心把它当「第 N 章」直接显示，不用做负数判断。
     */
    @Schema(description = "章节序号", example = "1")
    private Integer chapterNo;

    @Schema(description = "章节标题", example = "第一章 测试")
    private String title;

    /**
     * 正文字数 —— <b>保存正文时算出来的，不是作者手填的</b>。
     *
     * <p>算法是「去掉所有空白字符后的字符数」（见 {@code ChapterServiceImpl}），
     * 中文和英文都算 1 个字。这是网文行业的惯例口径，
     * 不追求和 Word 的「字数统计」完全一致。
     *
     * <p>★ 只建了章节还没写正文时是 {@code 0}，不是 {@code null}。
     */
    @Schema(description = "正文字数", example = "2345")
    private Integer wordCount;

    /**
     * 状态：<b>0草稿 1待审核 2已发布 4已驳回</b>。
     *
     * <p><b>★★ 取值是 0/1/2/4，【没有 3】—— 这里最容易照抄作品的状态写出 bug。</b>
     * 作品的状态是 0/1/2/3/4，其中 3 是「已下架」；
     * 章节不能单独下架（只能跟着作品一起下架），所以把它省掉了，
     * 驳回直接占 4 与作品对齐。见 {@code ChapterStatus}。
     * <p>后果：<b>任何「章节 status 是几」的映射表都不能从作品那份复制</b>，
     * 复制过去会把「已驳回」显示成「已下架」，而且不报错。
     * 为了避免每个前端各维护一份，接口直接给 {@code statusDesc}。
     */
    @Schema(description = "状态：0草稿 1待审核 2已发布 4已驳回。★ 没有 3", example = "2")
    private ChapterStatus status;

    /** 状态文案。★ 同 {@code NovelVO#statusDesc}，只用于显示，判断一律用上面的数字 */
    @Schema(description = "状态文案，★ 仅供显示", example = "已发布")
    private String statusDesc;

    /** 是否免费。★ 预留的 VIP 字段，P2 不参与任何逻辑，前端现在也不该拿它做判断 */
    @Schema(description = "是否免费：1 免费，0 收费。★ P2 预留，不参与逻辑", example = "1")
    private Integer isFree;

    /**
     * 通过审核的时间。
     *
     * <p><b>只有已发布的章节才有值</b>，草稿/待审/驳回时是 {@code null}。
     * 由于全局配了 {@code default-property-inclusion: non_null}，
     * <b>为 null 时这个字段在 JSON 里直接不出现</b>，前端取值要写 {@code ?? ''}。
     *
     * <p>注意它<b>不等于</b> {@code createTime}：一章可能草稿放三天才提交审核，
     * 两者差三天是正常的。读者端目录如果要显示「更新时间」，用的是这个。
     */
    @Schema(description = "通过审核的时间，未发布时为空", example = "2026-09-20 10:30:00")
    private LocalDateTime publishTime;

    /** 创建时间。★ 作者端「我的章节」列表按它排，读者端不显示 */
    @Schema(description = "创建时间", example = "2026-09-20 10:00:00")
    private LocalDateTime createTime;

    /* ==================== 构造 ==================== */

    /**
     * 从实体转换。
     *
     * @param novelTitle 所属作品书名，由调用方查好传进来（列表接口要批量查，
     *                   不能在这里逐条查 —— 那是 N+1）
     */
    public static ChapterVO of(Chapter entity, String novelTitle) {
        ChapterVO vo = new ChapterVO();
        vo.fillFrom(entity, novelTitle);
        return vo;
    }

    /**
     * ★ 把实体里属于「章节元信息」的那部分填进当前对象，由
     * {@link ChapterDetailVO} 复用。理由同 {@code NovelVO#fillFrom}：
     * <b>继承解决了字段的重复声明，赋值语句的重复要靠这个方法解决。</b>
     *
     * <p>{@code protected} 而不是 {@code public}：外部只该用静态工厂 {@link #of}。
     */
    protected void fillFrom(Chapter entity, String novelTitle) {
        this.setId(entity.getId());
        this.setNovelId(entity.getNovelId());
        this.setNovelTitle(novelTitle);
        this.setChapterNo(entity.getChapterNo());
        this.setTitle(entity.getTitle());
        this.setWordCount(entity.getWordCount());
        this.setStatus(entity.getStatus());
        this.setStatusDesc(entity.getStatus() == null ? null : entity.getStatus().getDesc());
        this.setIsFree(entity.getIsFree());
        this.setPublishTime(entity.getPublishTime());
        this.setCreateTime(entity.getCreateTime());
    }
}
