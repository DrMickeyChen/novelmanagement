package com.novel.modules.interaction.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.interaction.dto.HistoryReportDTO;
import com.novel.modules.interaction.service.HistoryService;
import com.novel.modules.interaction.vo.ReadHistoryItemVO;
import com.novel.modules.interaction.vo.ReadHistoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者端 —— 阅读进度。<b>需要登录。</b>
 *
 * <p><b>★★ 为什么这一组接口在 {@code /api/reader/**} 下，而阅读量埋点在
 * {@code /api/novels/{id}/view} 那个免登录前缀下：</b>
 * <p>判据只有一条 —— <b>这个接口落不落用户数据。</b>
 * <pre>
 *   POST /api/novels/{id}/view     不落用户数据（只 INCR 一个 Redis 计数器）→ 可以匿名
 *   POST /api/reader/history       往 read_history 写一行「这个用户读到哪」→ 必须登录
 * </pre>
 * 阅读进度是<b>带 user_id 的持久化数据</b>：匿名开放的话，要么写不了
 * （没有 user_id 可写），要么得凭空造一个身份 —— 前者是接口骗人，后者是灾难。
 * <p>反过来，也<b>不要</b>为了让埋点"统一"而把它挪到这里，
 * 那会让游客的阅读量完全统计不到（游客是读者端的主体）。
 *
 * <p><b>★ 这里没有 {@code @PreAuthorize}，也不需要。</b>
 * 访问控制由 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 兜住
 * （{@code /api/reader/**} 既不在白名单里、也没有专门的角色规则，
 * 所以「任何已登录用户都可以」正是我们想要的 —— 读者、作者、管理员都能看书）。
 * <p><b>真正的边界在 Service 里</b>：{@code userId} 一律取自 {@code UserContext}，
 * 两个接口都不接受调用方指定的用户 id。见 {@code HistoryServiceImpl}。
 *
 * <p><b>★ 和 {@code ReaderNovelController} 的分工：</b>
 * 那个类只管「公开的、不针对具体用户的」内容读取；
 * 这个类只管「当前用户自己的」行为数据。两者不重叠，
 * 二期拆服务时前者留在 {@code novel-content}，后者去 {@code novel-interaction}。
 *
 * @author MickeyChen
 */
@Tag(name = "15. 读者端 · 阅读进度", description = "阅读进度的上报与查询。★ 需要登录")
@RestController
@RequestMapping("/api/reader/history")
@RequiredArgsConstructor
public class ReaderHistoryController {

    private final HistoryService historyService;

    @Operation(
            summary = "上报阅读进度",
            description = """
                    **同一本书反复调用只会覆盖，不会新增行** ——
                    表上有唯一键 `uk_user_novel(user_id, novel_id)`，
                    由数据库裁决「一本书一条进度」。

                    ★ **成功返回的 `data` 是空的**，调用方不需要处理返回值。
                    阅读器每滚动 1 秒防抖上报一次，服务端不返回任何东西。

                    **★★ 前端的调用处必须带 `{ silent: true }`。**
                    不带的话，token 过期时全局响应拦截器会清登录态 +
                    整页跳转登录页 —— **读者正在往下滚，页面突然跳走，进度全丢**。
                    这个场景本地几乎测不出来（开发环境 token 很少过期）。

                    **校验（缺一不可）**：作品已上架（否则 `2001`）→
                    章节属于这本书（否则 `3001`）→ 章节已发布（否则 `3001`）。
                    `progress` 超出 `0~100` **会被夹到边界，不会报错**。

                    **★ 需要登录**（游客的进度只写浏览器 localStorage，不发这个请求）。""")
    @PostMapping
    public Result<Void> report(@Valid @RequestBody HistoryReportDTO dto) {
        historyService.report(dto);
        return Result.success("已记录", null);
    }

    @Operation(
            summary = "我的阅读历史",
            description = """
                    按**最后阅读时间倒序**返回（同秒按 id 兜底，否则翻页顺序会跳）。

                    ★ 每一行带书名 / 封面 / 作者 / 分类，以及
                    `chapterNo` + `chapterTitle` + `progress` ——
                    光有 novelId 的列表页是渲染不出来的，而让前端逐个去查
                    就是 N+1 搬到了浏览器里。

                    **★★ `available: false` 是合法状态**：这本书已经下架或被删了，
                    于是 `title` / `cover` 等字段**整个 key 消失**
                    （全局 `default-property-inclusion: non_null`），
                    只剩 `novelId` / `progress` / `updateTime`。
                    渲染成「已下架」而不是把这一行删掉。

                    ★ `chapterId` 与 `chapterNo` / `chapterTitle` **同生共死**：
                    读过的章节后来被作者撤回了，三个一起消失。

                    ★ **刻意不复用 `GET /api/reader/history/{novelId}`** ——
                    那是阅读器每次上报进度后都要调的接口，
                    给它加这三个字段就是每本书一次查询的开销，
                    而阅读器一个都用不上。

                    **★ 需要登录。**""")
    @GetMapping
    public Result<PageResult<ReadHistoryItemVO>> myPage(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "20") long size) {
        return Result.success(historyService.myPage(page, size));
    }

    @Operation(
            summary = "查询阅读进度",
            description = """
                    取**当前登录用户**在某本书上的进度。

                    ★ **查不到返回 `data: null`，这是正常情况不是错误** ——
                    说明这本书还没读过，或者记录已经被清掉。
                    全局配了 `default-property-inclusion: non_null`，
                    所以 JSON 里 **`data` 这个 key 会整个消失**，前端写
                    `res.data ?? null` 即可。

                    ★ **不会因为作品被下架而报错**，只会返回查不到的记录。
                    这个接口只可能返回调用者自己的那一行，乱传 `novelId`
                    也扫不出别人的数据，所以不需要（也不该）做存在性校验。

                    **★ 需要登录。**""")
    @GetMapping("/{novelId}")
    public Result<ReadHistoryVO> get(
            @Parameter(description = "作品 id") @PathVariable Long novelId) {
        return Result.success(historyService.get(novelId));
    }
}
