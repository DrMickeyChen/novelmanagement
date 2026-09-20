package com.novel.modules.content.vo;

import com.novel.common.enums.NovelStatus;
import com.novel.modules.content.entity.Novel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作品<b>列表</b>出参。读者端 / 作者端 / 管理端三处共用。
 *
 * <p><b>★★ 这个类里没有 {@code intro}（简介），这是刻意的，不要顺手加。</b>
 * 列表页一页 20 条，每条简介最多 1000 字 —— 加上去就是每页多传几十 KB，
 * 而列表页<b>一个字都不显示简介</b>。简介属于详情，见 {@link NovelDetailVO}。
 * <p>「宁可多传一个字段」在这里不成立：这不是一个字段的事，是<b>每页 ×20</b> 的事，
 * 而且移动端（读者端将来要适配手机）流量是要花钱的。
 *
 * <p><b>★ 同样没有 {@code auditRemark}（驳回理由）</b>：它是给作者看的一次性信息，
 * 属于详情。<b>尤其不能进读者端</b> —— 「驳回理由」是审核内部的话术
 * （「简介与正文不符，请修改后重新提交」），泄露给读者很荒唐。
 * 当然读者端按 C-7 约束 1 只会查到已上架的书，本来也不会有驳回理由，
 * 但依赖「数据恰好为空」不是防线，<b>不返回它才是防线</b>。
 *
 * <p><b>为什么 {@code categoryName} / {@code authorName} 放在 VO 里而不是前端自己查：</b>
 * 前端要拿它们得再发 N 个请求（N 本书 N 个作者），这就是经典的 N+1 问题搬到前端。
 * 后端一次 join 或者一次批量查昵称就解决了（批量查询见
 * {@code ContentUserLookup#nicknamesOf}，就是为了避开这个 N+1 才写的）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作品列表项")
public class NovelVO {

    @Schema(description = "作品 id", example = "1")
    private Long id;

    @Schema(description = "书名", example = "测试书")
    private String title;

    @Schema(description = "封面 URL", example = "https://example.com/cover.jpg")
    private String cover;

    @Schema(description = "作者 id", example = "2")
    private Long authorId;

    /**
     * 作者昵称 —— 由 {@code ContentUserLookup} 去 {@code sys_user} 查出来的，
     * <b>不是 {@code novel} 表里的字段</b>。
     *
     * <p>★ 注意这里用的是<b>昵称</b>而不是 {@code username}：读者看到的是
     * 「测试作者」这种笔名，登录名（{@code author01}）是账号信息，
     * 对读者没有意义，而且它是对外可见的登录凭据的一半，能不暴露就不暴露。
     */
    @Schema(description = "作者昵称", example = "测试作者")
    private String authorName;

    @Schema(description = "分类 id", example = "1")
    private Long categoryId;

    @Schema(description = "分类名称", example = "玄幻")
    private String categoryName;

    /**
     * 状态：0草稿 1待审核 2已上架 3已下架 4已驳回。
     *
     * <p>★ <b>以数字对外</b>（{@code NovelStatus.code} 上的 {@code @JsonValue} 决定），
     * 前端拿到的就是 {@code 2}。要回传给接口时也传数字 ——
     * 实测传 {@code "PUBLISHED"} 会得到 HTTP 400，详见 {@code NovelStatusDTO} 的类注释。
     */
    @Schema(description = "状态：0草稿 1待审核 2已上架 3已下架 4已驳回", example = "2")
    private NovelStatus status;

    /**
     * 状态的中文文案，如「已上架」。
     *
     * <p><b>★ 只用于显示，不要拿它做判断。</b>判断一律用上面的 {@code status} 数字 ——
     * 文案是会改的（「已上架」可能改成「连载中」），拿文案当条件的话，
     * 改一次文案就会静默地让某个分支永不命中。
     *
     * <p>给它是为了让三个前端不必各自维护一份 {@code 0→'草稿'} 的映射表
     * （那份映射表改起来要改三处，而且没人会记得）。
     */
    @Schema(description = "状态文案，★ 仅供显示", example = "已上架")
    private String statusDesc;

    /**
     * 已发布章节数。
     *
     * <p>★ 这是 {@code novel} 表上的<b>冗余字段</b>，由
     * {@code NovelServiceImpl#syncNovelStat} 重算维护，
     * 只在章节发布状态变化时刷新。<b>不要在这里现算</b> ——
     * 列表 20 条就是 20 次 {@code COUNT}，而且算出来的值可能和详情页不一致。
     *
     * <p>口径是「<b>已发布</b>章节数」，草稿章不算 ——
     * 作者在作者端看到的也是这个数，所以作者不会把草稿章算进「我写了 10 章」。
     */
    @Schema(description = "已发布章节数", example = "12")
    private Integer chapterCount;

    @Schema(description = "已发布章节总字数", example = "34567")
    private Integer wordCount;

    /** 阅读量。★ P2 恒为 0，P3 做阅读器时才有值 */
    @Schema(description = "阅读量。P2 尚未统计，恒为 0", example = "0")
    private Integer viewCount;

    @Schema(description = "是否完结：0 连载，1 完结", example = "0")
    private Integer isFinish;

    /**
     * 最后更新时间 —— 取的是<b>最新一个已发布章节</b>的发布时间，
     * 不是 {@code novel.update_time}（那是「这一行记录最后被改的时间」，
     * 管理端改个分类也会变，拿它排「最近更新」会排出一堆假更新）。
     *
     * <p>读者端按它倒序排，所以它可能为 {@code null}：
     * 一本还没发布过任何章节的书没有「最后更新时间」。
     * 由于全局配了 {@code default-property-inclusion: non_null}，
     * <b>为 null 时这个字段在 JSON 里直接不出现</b>，前端取值要写 {@code ?? ''}。
     */
    @Schema(description = "最后更新时间（最新已发布章节的时间），可能为空", example = "2026-09-20 10:30:00")
    private LocalDateTime lastUpdateTime;

    /**
     * 从实体转换。
     *
     * <p>{@code categoryName} / {@code authorName} 要由调用方查好传进来 ——
     * 这个 VO 不查库。理由：列表接口一次转 20 条，
     * 如果 {@code of()} 内部逐条查分类和作者，就是 40 次查询；
     * 调用方（Service）可以一次批量查完再转。
     * <b>转换方法里带 IO 是 VO 最容易变慢的地方。</b>
     */
    public static NovelVO of(Novel entity, String categoryName, String authorName) {
        NovelVO vo = new NovelVO();
        vo.fillFrom(entity, categoryName, authorName);
        return vo;
    }

    /**
     * ★ <b>把实体里属于「列表项」的那部分字段填进当前对象</b>，由
     * {@link NovelDetailVO} 复用。
     *
     * <p><b>这个方法存在的唯一理由是让「继承」真的成立。</b>
     * 如果没有它，{@code NovelDetailVO} 会写成
     * 「{@code extends NovelVO} + 自己的 {@code of()} 里把父类 14 个字段再赋值一遍」——
     * 那继承就白做了：父类加一个字段，详情那边照样漏。
     * 字段继承解决了「多声明一遍」，<b>但赋值语句的重复要靠这个方法解决</b>。
     *
     * <p>{@code protected} 而不是 {@code public}：外部只该用静态工厂
     * {@link #of}，不该拿一个空 VO 自己填 —— 那样漏填哪个字段编译器不管，
     * 而漏填的后果是接口少返回一个字段，前端拿到 {@code undefined}。
     *
     * @param categoryName 分类名，查不到可传 {@code null}
     * @param authorName   作者昵称，查不到可传 {@code null}
     */
    protected void fillFrom(Novel entity, String categoryName, String authorName) {
        this.setId(entity.getId());
        this.setTitle(entity.getTitle());
        this.setCover(entity.getCover());
        this.setAuthorId(entity.getAuthorId());
        this.setAuthorName(authorName);
        this.setCategoryId(entity.getCategoryId());
        this.setCategoryName(categoryName);
        this.setStatus(entity.getStatus());
        this.setStatusDesc(entity.getStatus() == null ? null : entity.getStatus().getDesc());
        this.setChapterCount(entity.getChapterCount());
        this.setWordCount(entity.getWordCount());
        this.setViewCount(entity.getViewCount());
        this.setIsFinish(entity.getIsFinish());
        this.setLastUpdateTime(entity.getLastUpdateTime());
    }
}
