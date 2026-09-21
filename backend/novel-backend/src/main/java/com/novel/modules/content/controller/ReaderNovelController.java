package com.novel.modules.content.controller;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.content.service.ChapterService;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.service.NovelViewCounter;
import com.novel.modules.content.vo.ChapterBriefVO;
import com.novel.modules.content.vo.ChapterDetailVO;
import com.novel.modules.content.vo.ChapterVO;
import com.novel.modules.content.vo.NovelDetailVO;
import com.novel.modules.content.vo.NovelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 读者端 —— 作品浏览。<b>匿名可访问</b>（在 {@code SecurityConfig}
 * 里按 HTTP 方法放行，见下面的说明）。
 *
 * <p><b>★★ 这个类是整个系统唯一「不登录也能拿数据」的业务接口，
 * 所以它的每一条查询都必须自己带可见性条件（大纲 §5.3 约束 1 和 2）。</b>
 * 它不依赖任何登录态 —— 没有 {@code UserContext} 可以兜底，
 * 一旦条件漏了，泄露是<b>直接对外</b>的，没有任何一层能拦住。
 *
 * <p><b>★ 为什么放行的是「这个前缀下的 GET」而不是整个 {@code /api/novels/**}：</b>
 * P2 时这里挂的是路径级白名单，而路径级白名单<b>不区分 HTTP 方法</b> ——
 * 意味着以后任何人往这个前缀下加一个 {@code DELETE /api/novels/{id}}，
 * 都会静默匿名放行。P3 改成了 {@code requestMatchers(HttpMethod.GET, ...)}
 * 加一条精确的 {@code POST /*&#47;view}（见 {@code SecurityConfig}）。
 * 于是「新加一个 GET 自动公开、新加一个写接口自动要求登录」，
 * 默认值终于是安全的。<b>不要把这个前缀加回路径级白名单。</b>
 *
 * <p><b>★★ 读者端不写用户数据。</b>这个前缀下没有改动任何业务表的接口 ——
 * 唯一的写是阅读量埋点 {@code POST /api/novels/{id}/view}，
 * 它<b>不落库</b>（只对一个 Redis 计数器 INCR，由定时任务异步汇总）、
 * <b>不产生用户数据</b>（无 INSERT、无唯一键、不记录调用者）、
 * <b>不放宽任何读的口径</b>（对不存在的书与未上架的书响应完全一样），
 * 因此可以匿名开放。三条里少一条，它就必须挪到需要登录的前缀下。
 *
 * <p><b>★ 会落用户数据的写接口（评论、书架、阅读进度）仍然必须放在
 * 需要登录的 {@code /api/reader/**} 下，不要往这个已经免登录的路径下加。</b>
 *
 * @author MickeyChen
 */
@Slf4j
@Tag(name = "12. 读者端 · 作品", description = "读者浏览作品。**无需登录**")
@RestController
@RequestMapping("/api/novels")
@RequiredArgsConstructor
public class ReaderNovelController {

    private final NovelService novelService;
    private final ChapterService chapterService;
    private final NovelViewCounter novelViewCounter;

    @Operation(
            summary = "作品列表",
            description = """
                    **只返回已上架的作品**，而且这个条件写在 SQL 的 WHERE 里 ——
                    不是查出来再过滤。后者在列表接口上必然漏：
                    混进一本未上架的书不会报错，只是列表里悄悄多一行。

                    参数全部可选。`sort` 支持 `latest`（默认，最后更新）/ `new`（最新创建）/
                    `words`（字数最多），**传不认识的值不会报错，按默认排序处理**。

                    **无需登录。**""")
    @GetMapping
    public Result<PageResult<NovelVO>> list(
            @Parameter(description = "分类 id，不传=全部")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "书名关键词，模糊匹配")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "是否完结：0 连载，1 完结，不传=全部")
            @RequestParam(required = false) Integer isFinish,
            @Parameter(description = "排序：latest（默认）/ new / words")
            @RequestParam(required = false) String sort,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(novelService.readerPage(categoryId, keyword, isFinish, sort, page, size));
    }

    /*
     * ★ 这个映射刻意写在 detail 的 /{id} 【之前】。
     *
     * 实际上 Spring 的 PathPattern 比较器里字面量优先于路径变量，
     * 所以 /rank 无论声明在哪都会稳定命中这里，不会掉进 /{id} ——
     * 也就是说顺序【不是】正确性的必要条件。
     * 但把它写在前面，读代码的人不用先去确认那条规则；
     * 而它一旦被误删或改名，第二个发现问题的会是报「Failed to convert
     * 'rank' to Long」的人 —— 那个错误信息完全指不到真正的原因。
     */
    @Operation(
            summary = "榜单",
            description = """
                    三个榜共用一个接口，用 `type` 区分。**都不分页** —— 榜单就是「前 N 名」。

                    | type | 含义 |
                    |---|---|
                    | `hot`（默认）| 阅读量降序 |
                    | `new` | 创建时间降序 |
                    | `finished` | 已完结的作品，再按阅读量降序 |

                    ★ `type` **传不认识的值不报错**，按 `hot` 处理（和列表接口的 `sort` 一个约定）。

                    ★ `limit` 默认 10，**会被夹到 1~50**。

                    ★ 只含已上架的作品。

                    **无需登录。**""")
    @GetMapping("/rank")
    public Result<List<NovelVO>> rank(
            @Parameter(description = "榜单类型：hot（默认）/ new / finished")
            @RequestParam(required = false) String type,
            @Parameter(description = "返回条数，默认 10，上限 50")
            @RequestParam(required = false) Integer limit) {

        return Result.success(novelService.rank(type, limit));
    }

    @Operation(
            summary = "作品详情",
            description = """
                    ★ **P6 起不再返回 `chapters`** —— 目录改成独立的分页接口
                    `GET /api/novels/{id}/chapters`，翻页浏览目录请调那一个。
                    详情里只给 `firstChapterId`，够「开始阅读」按钮用。

                    改动理由：几百章的书每打开一次详情就要拖回全部章节，
                    而详情页是**匿名、高频**的浏览入口。

                    ★ **刻意不区分「作品不存在」和「作品未上架」，两者都返回 `2001`。**
                    否则匿名调用者可以靠错误码的差别枚举出「哪些 id 是真实存在的书」——
                    这个接口不需要登录，谁都能扫。

                    作者要看自己没上架的书，请走 `GET /api/author/novels/{id}`。

                    **无需登录。**""")
    @GetMapping("/{id}")
    public Result<NovelDetailVO> detail(@PathVariable Long id) {
        return Result.success(novelService.readerDetail(id));
    }

    @Operation(
            summary = "作品目录（分页）",
            description = """
                    详情页的目录用这个接口翻页。**只列已发布的章节** ——
                    草稿章、待审章、被驳回的章都不会出现。

                    按 `chapterNo` **正序**（第 1 章在最前），这是读目录的顺序；
                    分页参数 `page` / `size` 走 `buildPage` 兜底（`size` 夹到 1~100）。

                    ★ `total` 就是这本书的**已发布章节数**，前端显示「共 N 章」用它，
                    **不要**再回详情接口要一个章节总数 —— 那个数会在两个地方各维护一遍。

                    ★ **作品不存在 / 未上架返回 `2001`**，和详情接口一字不差
                    （理由见上）。目录接口不该变成「这个 novelId 真不真」的探针。

                    **无需登录。**""")
    @GetMapping("/{id}/chapters")
    public Result<PageResult<ChapterVO>> chapters(
            @PathVariable Long id,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，1~100") @RequestParam(defaultValue = "20") long size) {

        return Result.success(novelService.chapterPage(id, page, size));
    }

    @Operation(
            summary = "作品目录全量（精简）",
            description = """
                    ★ **这个接口是给阅读器用的，不是详情页用的。**

                    阅读器和详情页要的目录**不是同一种东西**：
                    详情页是「翻页浏览」（分页），而阅读器要回答的是
                    「我在第几章、前后各是哪一章」—— 它需要**整条有序列表**，
                    分页给不了（下一页的那一章是不是紧邻的，分页接口不知道）。

                    所以这里返回全量，但**只有 `id` / `chapterNo` / `title`**，
                    一条 40 字节左右。`ChapterVO` 那些 `status` / `wordCount` /
                    `novelTitle` 对阅读器的目录抽屉没有意义。

                    ★ **和上面的分页接口不是重复的，别合并**：合并的话，
                    要么阅读器得翻 N 页才拼得出上一章/下一章，
                    要么详情页得拖回整本目录 —— 两个方向的代价都比多一个接口大。

                    只列已发布的章节；作品不存在 / 未上架返回 `2001`。

                    **无需登录。**""")
    @GetMapping("/{id}/catalog")
    public Result<List<ChapterBriefVO>> catalog(@PathVariable Long id) {
        return Result.success(novelService.catalog(id));
    }

    @Operation(
            summary = "章节正文（阅读器核心接口）",
            description = """
                    **这是读者端唯一返回正文的接口** —— 阅读器就是靠它翻页的。

                    **★★ 必须同时满足两个条件，少一个都是真实的越权读：**

                    1. **作品已上架**。不查作品、只查章节的话，作品下架后它的正文
                       **照常匿名可读** —— 而下架往往正是因为这本书的内容有问题。
                    2. **章节已发布，且确实属于 URL 上的那本书**。只按 `cid` 查的话，
                       把 `cid` 换成别的书的章节 id，就能从一本已上架的书
                       **读到另一本书（可能未上架）的章节**，URL 上的 `{id}` 形同虚设。

                    以上任一不满足都返回 `2001` / `3001` 这类「不存在」——
                    **不区分「未上架」**，理由同详情接口：这个接口也免登录，
                    错误码的差别会变成一把枚举探针。

                    ★ **不返回 `auditRemark`**（驳回理由是审核内部话术，不该给读者看）。

                    **无需登录。**""")
    @GetMapping("/{id}/chapters/{cid}")
    public Result<ChapterDetailVO> chapterDetail(@PathVariable Long id, @PathVariable Long cid) {
        return Result.success(chapterService.readerDetail(id, cid));
    }

    @Operation(
            summary = "阅读量埋点",
            description = """
                    读者打开某本书时调用一次。**这是读者端唯一一个写接口，也是全站唯一一个匿名 POST。**

                    ★ **它永远返回成功**，即使 `id` 不存在或作品未上架 —— 详见下面的说明。
                    调用方（阅读器）不需要处理任何错误。

                    ★ 阅读量**不是实时**的：这里只对一个 Redis 计数器做 `INCR`，
                    由定时任务每 30 秒批量落库到 `novel.view_count`。
                    所以刚点完去看榜单，数字可能还没变。

                    **无需登录。**""")
    @PostMapping("/{id}/view")
    public Result<Void> recordView(@PathVariable Long id) {
        try {
            novelService.requirePublished(id);
        } catch (BusinessException e) {
            /*
             * ★★ 这个接口【永远返回 200】，未知 id 也一样 —— 这里把
             * NOVEL_NOT_FOUND 吃掉，而不是让它冒到全局异常处理器变成 code 2001。
             *
             * 三条理由：
             *
             * ① 它是埋点，调用方没有任何补救动作可做。让它返回错误，
             *    等于逼每一个调用处为一件不重要的事写错误处理 ——
             *    而漏写的那一处不会报错，只是阅读量少记了。
             *
             * ② ★ 更重要的：返回错误码会让这个接口变成一把枚举探针。
             *    requirePublished 对「不存在」和「未上架」都抛 2001，
             *    信息量上和 GET /api/novels/{id} 一样（那个接口是公开的，
             *    所以不构成新的泄露）—— 但这是个【更便宜】的探针：
             *    它只做一次主键查询，不拼 VO、不查目录。
             *    对一个没有限流的匿名接口来说，把「响应完全相同」做到极致
             *    比「错误码一致」更结实：不管 id 是什么、书是什么状态，
             *    响应体一个字节都不差。
             *
             * ③ 埋点失败本来就不该让读者的页面出错。阅读量丢一点无所谓，
             *    读者打不开书才是事故。
             *
             * ★ requirePublished 只有这一个失败模式，所以这里不需要再判 code。
             */
            log.debug("阅读量埋点：作品不存在或未上架，忽略 | novelId={}", id);
            return Result.success();
        }

        // 走到这里说明书存在且已上架。record 内部自己吞异常（Redis 挂了也不影响读者）。
        novelViewCounter.record(id);
        return Result.success();
    }
}
