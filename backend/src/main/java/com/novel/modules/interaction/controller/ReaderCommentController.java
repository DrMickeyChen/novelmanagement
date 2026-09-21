package com.novel.modules.interaction.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.interaction.dto.CommentCreateDTO;
import com.novel.modules.interaction.dto.CommentReplyDTO;
import com.novel.modules.interaction.service.CommentService;
import com.novel.modules.interaction.vo.CommentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者端 —— 评论。<b>四个接口里有三个需要登录，一个匿名。</b>
 *
 * <p><b>★★ 这个类【没有】类级 {@code @RequestMapping}，每个方法各写全路径。</b>
 * 看起来和项目里其它 Controller 不一致，但这是被路径形状逼出来的：
 * <pre>
 *   GET    /api/novels/{novelId}/comments                        按【作品】定位
 *   POST   /api/novels/{novelId}/comments                        按【作品】定位
 *   GET    /api/novels/{novelId}/chapters/{chapterId}/comments   按【作品 + 章节】定位
 *   POST   /api/novels/{novelId}/chapters/{chapterId}/comments   按【作品 + 章节】定位
 *   POST   /api/comments/{id}/like                               按【评论】定位
 *   DELETE /api/comments/{id}/like                               按【评论】定位
 *   DELETE /api/comments/{id}                                    按【评论】定位
 *   POST   /api/comments/{parentId}/replies                      按【评论】定位
 *   GET    /api/novels/{novelId}/comments/{commentId}/replies    按【作品 + 评论】定位
 * </pre>
 * 两族路径的第一段就不一样（{@code novels} / {@code comments}），
 * Spring 的类级前缀是<b>拼</b>在方法路径前面的、不能覆盖，
 * 所以只要写了类级前缀，另外那一族就会变成
 * {@code /api/novels/api/comments/...}。
 *
 * <p>拆成两个类也行，但那样「发表评论」和「点赞评论」会落到两个文件里 ——
 * 它们用同一个 Service、同一套登录要求、同一族出参 VO，
 * 拆开只会让「评论相关的读者接口有哪些」这个问题需要看两个地方才能回答。
 *
 * <p><b>★★ 八个接口里 P6 新增的四个，同样【不需要改 SecurityConfig】——</b>
 * 这不是巧合，是选路径时就照着规则挑的：
 * <ul>
 *   <li>{@code GET /api/novels/**} —— 被
 *       {@code .requestMatchers(HttpMethod.GET, "/api/novels/**").permitAll()}
 *       覆盖，<b>自动就是匿名的</b>。这正是 P3 把白名单从「路径级」改成
 *       「方法级」换来的：新加一个读者端的 GET 不需要回来登记，
 *       而新加一个写接口会自动要求登录（默认值终于是安全的）。
 *       <p>★ P6 的两条 GET（章评列表、展开回复）<b>都挂在这个前缀下</b>，
 *       就是为了拿到这条规则 —— 章评区是匿名可见的，而挂到
 *       {@code /api/chapters/**} 或 {@code /api/comments/**} 下的话，
 *       它们会直接变成「需要登录」，且<b>只有真的用游客点一次才会发现</b>。</li>
 *   <li>{@code POST} / {@code DELETE} 那四个在 {@code /api/comments/**} 下，
 *       既不在白名单里、也没有专属的角色规则，于是落到
 *       {@code anyRequest().authenticated()} —— <b>「任何已登录用户都可以」</b>，
 *       正是我们要的（读者、作者、管理员都能评论）。
 *       ★ 反过来也说明：这几个写接口【绝不能】把路径放到
 *       {@code /api/novels/**} 下面 —— 那会撞上旁边那条
 *       {@code permitAll} 的 GET 规则，至于 POST 能不能匿名，
 *       就得看有没有人记得去改 {@code SecurityConfig} 了。
 *       <b>把写接口放在「没有专门规则」的前缀下，比放在一个有规则的旁边安全。</b></li>
 * </ul>
 *
 * <p><b>★ 发章评为什么是 {@code POST /api/novels/{novelId}/chapters/{chapterId}/comments}
 * 而不是 {@code POST /api/chapters/{chapterId}/comments}：</b>
 * 后者要能匿名 GET 就得往 {@code SecurityConfig} 里再加一条
 * {@code GET /api/chapters/**} —— 而读者端<b>本来就在这本书的上下文里</b>
 * （页面路由上两个 id 都有），路径和阅读器读章节的
 * {@code GET /api/novels/{nid}/chapters/{cid}} 同形，少一条白名单规则。
 * ★ 代价是 GET 只用得上 {@code chapterId}（章评的 {@code target_id} 就是它，
 * 全局唯一，没有再按 novelId 筛一遍的意义）；{@code novelId} 在<b>写入</b>时
 * 才真正用上 —— {@code requireChapterInNovel} 靠它挡住
 * 「拿别的书的章节 id 来刷评论」。</p>
 *
 * <p><b>★ 真正的边界在 Service 里</b>：{@code userId} 一律取自
 * {@code UserContext}，八个接口都不接受调用方指定的用户 id。
 *
 * <p><b>★★ 读者端【看不到】自己刚发的评论。</b>
 * 新评论落库时状态恒为「待审核」，而 {@code GET} 只返回已显示的。
 * 这不是 bug：审核制的产品里，「我发出去了」和「别人能看见」本来就是两件事。
 * P5 没有「我的评论」页面（决定 11），所以前端现在只需提示一句
 * 「评论已提交，等待审核」。
 *
 * @author MickeyChen
 */
@Tag(name = "19. 读者端 · 评论", description = "书评的浏览、发表、点赞、删除。★ 列表无需登录")
@RestController
@RequiredArgsConstructor
public class ReaderCommentController {

    private final CommentService commentService;

    @Operation(
            summary = "某本书的评论列表（分页）",
            description = """
                    **无需登录。**

                    ★ 只返回 `status = 1`（已显示）的评论，条件写在 SQL 的 WHERE 里 ——
                    待审和已隐藏的**不可能**出现在结果中。

                    ★ **按 `id` 倒序 = 最新的在最上面**。用 `id` 而不是 `create_time`
                    是因为同一秒发出的两条评论按时间排不稳定，翻页时会看到
                    某一条重复出现、另一条永远看不到。

                    ★ **不校验作品是否存在 / 是否上架**，任何 `novelId` 都返回一个列表
                    （可能是空的）。这是刻意的：不校验的响应只有一种形态，
                    校验之后「2001 作品不存在」和「空列表」的差别就成了一份
                    「某个 novelId 存不存在」的探针 —— 而这个接口是匿名的，
                    正是最不该提供探针的地方。

                    ★ `nickname` 可能为空（评论人已注销）。
                    全局配了 `default-property-inclusion: non_null`，
                    **这个 key 会整个消失**，前端写 `row.nickname ?? '已注销用户'`。

                    ★ 出参**不含** `userId` / `status` / `auditRemark` ——
                    用的是 `CommentVO.forReader` 而不是 `forAdmin`。""")
    @GetMapping("/api/novels/{novelId}/comments")
    public Result<PageResult<CommentVO>> list(
            @Parameter(description = "作品 id") @PathVariable Long novelId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(commentService.readerPage(novelId, page, size));
    }

    @Operation(
            summary = "发表评论",
            description = """
                    **需要登录。★ 有 10 秒频控。**

                    `{"content": "写得很好", "score": 5}`
                    - `content` **必填**，1~500 字（`@NotBlank`，所以 `"   "` 也会被拒）
                    - `score` **可选**，1~5。不传就是不打分。
                      **越界（0 / 99）会被拒，不会夹到边界** ——
                      它来自一个五选一的控件，越界说明调用方坏了；
                      而且夹一下就把一个错误的分洗成了合法的分，更难查。

                    ★ **落库状态恒为「待审核」**，读者看不到自己刚发的评论。
                    审核通过后才会出现在上面的列表里。
                    DTO 里**没有** `status` 字段 —— 让调用方能指定状态，
                    就是让读者自己把评论设成「已显示」，审核形同虚设。

                    **返回新评论的 id**，前端据此提示「评论已提交，等待审核」。

                    **校验**：作品必须存在且已上架（否则 `2001`，
                    且「不存在」和「未上架」返回同一个码）。

                    **★ 频控（`4004`）**：同一用户 10 秒内第二次调用会被拒。
                    - 按 **`userId`** 限流，**不按 IP** —— 一套 NAT 后面整个公司
                      共用一个出口 IP，按 IP 限流会让正常用户互相误伤
                    - 用的是 `SET key value NX EX 10` **一条命令**。
                      写成「先 GET 判断再 SET」是两步非原子操作，**连点两下能同时通过**
                    - ★ **限流在最前面执行**（先占坑再干活），所以一条**写错的**
                      评论也会占掉 10 秒的额度。这是刻意选的：限流器的作用是
                      限制「一个用户能造成多少工作量」，先干活再限流等于没限，
                      而代价只是「改完错别字要等 10 秒」""")
    @PostMapping("/api/novels/{novelId}/comments")
    public Result<Long> create(@Parameter(description = "作品 id") @PathVariable Long novelId,
                              @Valid @RequestBody CommentCreateDTO dto) {
        return Result.success("评论已提交，等待审核", commentService.create(novelId, dto));
    }

    @Operation(
            summary = "某一章的章评列表（分页）",
            description = """
                    **无需登录。**

                    ★ 和书评列表**同一条规矩**：只返回 `status = 1`（条件写在 SQL 的
                    WHERE 里）、按 `id` 倒序、**不校验章节是否存在 / 是否发布**
                    （不校验的响应只有一种形态，校验之后就是一份
                    「某个 chapterId 真不真」的探针，而这个接口是匿名的）。

                    ★ **只返回一级评论**，每条带最多 3 条二级回复（`replies`）
                    和回复总数（`replyCount`）。要看全部回复调
                    `GET /api/novels/{novelId}/comments/{commentId}/replies`。

                    ★ 路径上的 `{novelId}` **不是筛选条件** —— 章评的 `target_id`
                    就是 `chapterId`（全局唯一），查询只用它。
                    这个 id 在**发表**章评时才真正用上（用来挡住
                    「拿别的书的章节 id 来刷评论」）。路径写成这个形状，
                    是为了和阅读器读章节的 `/api/novels/{nid}/chapters/{cid}`
                    一致、并且自动落在匿名的 GET 白名单里。""")
    @GetMapping("/api/novels/{novelId}/chapters/{chapterId}/comments")
    public Result<PageResult<CommentVO>> chapterList(
            @Parameter(description = "作品 id") @PathVariable Long novelId,
            @Parameter(description = "章节 id") @PathVariable Long chapterId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(commentService.chapterReaderPage(chapterId, page, size));
    }

    @Operation(
            summary = "发表章评",
            description = """
                    **需要登录。★ 和发书评【共用】同一个 10 秒频控。**

                    `{"content": "这段打斗写得很稳", "score": null}` ——
                    ★ 章评也可以带 `score`，规则和书评完全一样（可选、1~5）。
                    它们进的是同一个 `novel.score`（那是**作品**的评分，不是章节的）。

                    ★ **校验比发书评多两道**：作品已上架（`2001`）→
                    章节属于这本书（`3001`）→ 章节已发布（`3001`）。
                    后两个返回同一个码是刻意的 —— 对读者来说
                    「这一章不在这本书里」和「这一章还没发布」是同一件事，
                    区分开就等于确认了「这个 id 是真的，只是没发布」。

                    ★ 落库状态恒为「待审核」，返回新评论的 id。

                    **★ 频控（`4004`）**：同一用户 10 秒内第二次调用会被拒，
                    **书评和章评共用这个额度** —— 各限各的等于额度直接翻倍。""")
    @PostMapping("/api/novels/{novelId}/chapters/{chapterId}/comments")
    public Result<Long> createChapterComment(
            @Parameter(description = "作品 id") @PathVariable Long novelId,
            @Parameter(description = "章节 id") @PathVariable Long chapterId,
            @Valid @RequestBody CommentCreateDTO dto) {

        return Result.success("评论已提交，等待审核",
                commentService.createChapterComment(novelId, chapterId, dto));
    }

    @Operation(
            summary = "回复一条评论（只支持两级）",
            description = """
                    **需要登录。★ 和发评论共用同一个 10 秒频控。**

                    `{"content": "同感，那段我也看了两遍"}` ——
                    ★ **没有 `score`**：回复不打分。让它打分，
                    评分就会被「嗯」「+1」这类回复一条条稀释，
                    而分数看起来一直在**动**、只是越动越低。

                    ★ **`targetType` / `targetId` 由服务端从父评论继承**，
                    调用方传不了也不用传 —— 传了就会出现「父是书评、子填了章评」
                    这种行，而它在列表里表现为**这条回复根本不出现**。

                    ★★ **只支持两级**：回复一条**回复**返回 `4006`，
                    不会静默挂到祖父下面。三级回复要做「回复 @某人」前缀和
                    递归渲染，产品价值远小于成本；而静默展平比明确拒绝更难解释 ——
                    用户明明回复的是 B，界面上却挂在 A 下面。

                    **失败**：父评论不存在 / 已删除 / 待审核 / 已隐藏
                    四种情况统一返回 `4001`（区分开等于确认
                    「这个 id 是真的，只是还没过审」，那是一份
                    「管理员审到哪了」的探针）。

                    **返回新回复的 id**，前端据此提示「已提交，等待审核」。""")
    @PostMapping("/api/comments/{parentId}/replies")
    public Result<Long> reply(@Parameter(description = "父评论 id") @PathVariable Long parentId,
                              @Valid @RequestBody CommentReplyDTO dto) {
        return Result.success("回复已提交，等待审核", commentService.reply(parentId, dto));
    }

    @Operation(
            summary = "展开某条评论的全部回复（分页）",
            description = """
                    **无需登录。**

                    ★ 列表接口每条一级评论最多内联 3 条回复，**这一条才是
                    「还有 5 条回复」的去处**。前端点「展开」时调它。

                    ★ **按 `id` 正序**（从早到晚）—— 和一级评论列表的倒序相反：
                    一级评论是「最新的最值得看」，一组回复是一段对话，正着读才对。

                    ★ 传一个**不存在的评论 id 会返回空列表，不是 4001** ——
                    这个接口没有「操作」任何东西，只是读「它下面有什么」，
                    而「没有回复」和「评论不存在」对调用方是同一件事。
                    （对照 `POST /api/comments/{id}/replies`：那里必须挡，
                    因为它在**写**一行指向这个 id 的数据。）

                    ★ 传一条**二级回复**的 id 同样返回空列表（它确实没有回复）。""")
    @GetMapping("/api/novels/{novelId}/comments/{commentId}/replies")
    public Result<PageResult<CommentVO>> replies(
            @Parameter(description = "作品 id（仅用于路由，不参与查询）") @PathVariable Long novelId,
            @Parameter(description = "一级评论 id") @PathVariable Long commentId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(commentService.replyPage(commentId, page, size));
    }

    @Operation(
            summary = "点赞（幂等）",
            description = """
                    **需要登录。同一个人点第二次不会重复计数。**

                    **返回点赞之后的总数**，前端直接用它更新按钮上的数字，
                    不需要再拉一次列表。

                    ★ **幂等**：已经点过的用户再调，仍然返回成功和当前总数
                    （用户的意图和第一次相同），不会自增。
                    `SADD` 返回 1 才算「第一次点」—— 单条命令，Redis 保证原子。

                    ★ **取消点赞是 `DELETE /api/comments/{id}/like`**（P6 补的）。
                    两条路径的差别只有一个 `/like` 后缀 —— 前端少写这一段
                    就从「取消点赞」变成「**删掉这条评论**」，而且两个都是 200、
                    都「成功」。见下面 `unlike` 的说明。

                    ★ 防重复点赞的依据**不在数据库里**（全库 22 张表没有
                    `comment_like` 表），而在 Redis 的 Set 上。所以：
                    **Redis 被清空之后，同一个用户可以再点一次，计数会虚高。**
                    点赞数是展示型数据，虚高 1 可以接受；
                    但「谁点过」这件事本身就不精确了 —— 涉及钱的场景不能照抄这套做法。

                    ★ **只能给「已显示」的评论点赞。** 待审 / 已隐藏 / 已删除
                    / 不存在，**四种情况返回同一个 `4001`** ——
                    区分开就等于确认了「这个 id 是真的，只是还没过审」，
                    而那是一份「管理员审到哪了」的探针。""")
    @PostMapping("/api/comments/{id}/like")
    public Result<Integer> like(@Parameter(description = "评论 id") @PathVariable Long id) {
        return Result.success(commentService.like(id));
    }

    @Operation(
            summary = "取消点赞（幂等）",
            description = """
                    **需要登录。没赞过也返回成功。**

                    **返回取消之后的总数**，前端直接用它更新按钮上的数字。

                    ★★ **它和上面的点赞【不是同一个方法的一对】，路径只差一个 `/like`
                    后缀的是下面那个 `DELETE /api/comments/{id}`（删评论）——**
                    那段路径少写一个后缀，用户以为在取消点赞，实际把评论删了，
                    而且响应同样是 200 + 成功。**这是本模块最容易误伤的地方。**
                    两个接口的返回体不同（这里返回数字、删评论返回 null），
                    但真正靠得住的是**路径写对**，而不是靠前端去分辨返回体。

                    ★ **幂等**：`SREM` 返回 0（本来就没赞过）仍然返回成功 ——
                    用户的意图是「让它变成未赞」，而它已经是了。

                    ★ **不需要判「是不是我的点赞」**：Set 的成员就是 userId，
                    这里删的只能是自己的那个成员。对照删评论 (`4002`) 必须判归属。

                    ★ **计数是重算的，不是 -1**：`like_count` 是 Redis Set 基数的投影。
                    Redis 丢过一次 Set 之后两者会分叉，`-1` 会把分叉原样留着，
                    重算则把它修回来（表现是数字变小）。理由写在
                    `CommentServiceImpl#refreshLikeCount`。

                    **失败**：评论不存在 / 待审核 / 已隐藏 / 已删除，统一 `4001`
                    （同点赞，理由在那里）。""")
    @DeleteMapping("/api/comments/{id}/like")
    public Result<Integer> unlike(@Parameter(description = "评论 id") @PathVariable Long id) {
        return Result.success(commentService.unlike(id));
    }

    @Operation(
            summary = "删除自己的评论（逻辑删）",
            description = """
                    **需要登录。只能删自己的。**

                    三种失败各有各的码，因为**它们的「下一步」不一样**：

                    | code | 含义 | 用户该做什么 |
                    |---|---|---|
                    | `4001` | 评论不存在 | 刷新页面 |
                    | `4002` | 只能删自己的评论 | 点错了 |
                    | `4003` | 已经被删过了 | 上一次其实成功了，什么都不用做 |

                    ★ 合成一句「操作失败」，用户就不知道自己该刷新还是该放弃。

                    ★ **归属校验在「是否已删除」之前** —— 顺序反了的话，
                    对一条「别人的、已删除的」评论会返回 `4003`，
                    对「别人的、正常的」返回 `4002`，这个差别本身就是探针。
                    现在无论哪种都是 `4002`。

                    ★ **是逻辑删**（`deleted = 1`，行还在）。三条理由各自都够：
                    ① 管理端要能查证「消失的评论」说了什么；
                    ② `audit_log` 里可能指向这条评论，物理删会留下悬空引用；
                    ③ 二期统计「曾经有多少条」时需要它。

                    ★ **管理端【没有】删除评论的接口** —— 管理员处置内容是「隐藏」
                    （`status = 2`），因为隐藏会留痕
                    （`audit_user_id` / `audit_time` / `audit_remark`），删除不会。""")
    @DeleteMapping("/api/comments/{id}")
    public Result<Void> delete(@Parameter(description = "评论 id") @PathVariable Long id) {
        commentService.delete(id);
        return Result.success("评论已删除", null);
    }
}
