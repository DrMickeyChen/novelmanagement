package com.novel.modules.interaction.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.interaction.service.ShelfService;
import com.novel.modules.interaction.vo.ShelfItemVO;
import com.novel.modules.interaction.vo.ShelfStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者端 —— 书架。<b>需要登录。</b>
 *
 * <p><b>★★ 加入和移出是两条路径、两个方法，不是「切换」。</b>
 * 一条 {@code POST /api/reader/shelf/{novelId}} 做成 toggle 看着更省事，
 * 但它既不幂等也不可重试：用户点「加入」、响应超时（服务端其实成功了）、
 * 客户端重试，第二次把书<b>移出</b>了，而两次响应都是 200。
 * 完整论证见 {@code ShelfService} 的类注释。
 *
 * <p><b>★ 为什么在 {@code /api/reader/**} 下，而不是大纲写的 {@code /shelf}：</b>
 * 判据同 {@code ReaderHistoryController} —— <b>这个接口落不落用户数据。</b>
 * 书架每一行都带 {@code user_id}，所以它必须登录；
 * 而 {@code /api/reader/**} 既不在 {@code SecurityConfig} 的白名单里，
 * 也没有专门的角色规则，正好落在 {@code anyRequest().authenticated()} 上 ——
 * 不用改一行安全配置。反过来，放到 {@code /api/**} 下就得手动加规则，
 * 而「加规则时写错一个通配符」是这类配置最常见的翻车方式。
 *
 * <p>★ <b>没有 {@code @PreAuthorize}，也不需要</b>：读者、作者、管理员都能有自己的书架。
 *
 * @author MickeyChen
 */
@Tag(name = "32. 读者端 · 书架", description = "加入 / 移出 / 我的书架。★ 需要登录")
@RestController
@RequestMapping("/api/reader/shelf")
@RequiredArgsConstructor
public class ReaderShelfController {

    private final ShelfService shelfService;

    @Operation(
            summary = "我的书架",
            description = """
                    按**加入时间倒序**返回（同秒按 id 兜底，否则翻页顺序会跳）。

                    ★ 每一行带**阅读进度**：`readChapterNo` / `readChapterTitle` /
                    `progress`，来自 `read_history`。书架页上光有书名没用，
                    读者要的是「继续阅读」。

                    **★★ `available: false` 是合法状态，前端必须接住** ——
                    这本书已经下架或被删了，于是 `title` / `cover` 等字段
                    **整个 key 消失**（全局 `default-property-inclusion: non_null`），
                    只剩 `novelId` / `createTime` / 进度。渲染成「已下架」占位卡，
                    **并且仍然要给出「移除」按钮**（移除接口不校验上架状态）。

                    ★ `readChapterId` 与 `readChapterNo` / `readChapterTitle`
                    **同生共死**：读过的章节后来被作者撤回了，三个一起消失。
                    这时「继续阅读」要跳到 `lastChapterId`，不能拿 `readChapterId` 跳。

                    **★ 需要登录。**""")
    @GetMapping
    public Result<PageResult<ShelfItemVO>> myPage(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "20") long size) {
        return Result.success(shelfService.myPage(page, size));
    }

    @Operation(
            summary = "查询书架状态",
            description = """
                    这本书在不在我的书架，加上它当前的收藏数。

                    ★ **不复用 `GET /api/novels/{id}`** —— 那个接口是**匿名**的
                    （游客也要能看详情页），把它和「我」的状态混在一起，
                    等于要求每个游客也有一个 userId。

                    ★ 作品不可见时 `collectCount` 整个 key 消失。
                    **★ 需要登录。**""")
    @GetMapping("/{novelId}")
    public Result<ShelfStatusVO> status(
            @Parameter(description = "作品 id") @PathVariable Long novelId) {
        return Result.success(shelfService.status(novelId));
    }

    @Operation(
            summary = "加入书架",
            description = """
                    **幂等**：本来就在书架里也返回成功，`collect_count` 不会重复增加。

                    ★ 作品必须**存在且已上架**，否则 `2001`
                    （和移出接口的口径刻意不同 —— 理由见那边的说明）。

                    ★ 返回的是**操作后的真实状态**，前端不需要自己翻转按钮：
                    对一本已在书架里的书再调一次，返回的仍然是 `onShelf: true`。

                    ★ 时间戳取**第一次**加入的时间：重复调用不会把这本书
                    顶到书架最上面。

                    **★ 需要登录。**""")
    @PostMapping("/{novelId}")
    public Result<ShelfStatusVO> add(
            @Parameter(description = "作品 id") @PathVariable Long novelId) {
        return Result.success("已加入书架", shelfService.add(novelId));
    }

    @Operation(
            summary = "移出书架",
            description = """
                    **幂等**：本来就不在书架里也返回成功。

                    **★★ 刻意不校验作品是否上架** —— 书架里完全可能有一本
                    已经下架的书，如果这里也要求「作品已上架」，
                    那一行就**永远移除不掉**：读者点「移除」拿到 `2001`，
                    书还在书架上，再点还是 `2001`。
                    既然列表里把它显示成「已下架」占位卡，就必须给出清掉它的路。

                    ★ 物理删除（这张表没有 `deleted` 列）：语义是
                    「现在在不在架」，留着历史行没有意义。

                    **★ 需要登录。**""")
    @DeleteMapping("/{novelId}")
    public Result<ShelfStatusVO> remove(
            @Parameter(description = "作品 id") @PathVariable Long novelId) {
        return Result.success("已移出书架", shelfService.remove(novelId));
    }
}
