package com.novel.modules.system.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.system.dto.NoticeSaveDTO;
import com.novel.modules.system.service.NoticeService;
import com.novel.modules.system.vo.NoticeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— 公告管理。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 这个类里没有 {@code @PreAuthorize}，也不用在 {@code SecurityConfig} 里登记。
 * <p>读者侧那两个接口在 {@code ReaderNoticeController} —— 它们需要
 * {@code SecurityConfig} 里一条单独的匿名白名单，
 * 所以<b>刻意放在另一个类里</b>：那个类名本身就说明了「这里的接口是公开的」，
 * 而如果混在本类里，将来有人给本类加 {@code @PreAuthorize} 时会顺手
 * 把读者端也保护起来，读者端首页立刻变成 401。
 *
 * <p><b>★★ 「发布 / 下线」没有单独的接口，走的就是 {@code PUT}。</b>
 * 公告没有状态机（来回切都合法、重复执行幂等），所以不需要
 * {@code /audit} 那样带动作语义的入口。前端的行内按钮
 * 把 {@code status} 翻一下、其余字段原样带上即可 ——
 * 和分类管理页的「启用 / 停用」同一套做法。
 *
 * <p><b>★ 发布时间 {@code publishTime} 不由前端传</b>，它由后端在
 * 「未发布 → 已发布」那一刻盖上去。理由见 {@code NoticeSaveDTO} 的类注释。
 *
 * @author MickeyChen
 */
@Tag(name = "30. 管理端 · 公告管理", description = "公告的增删改查 + 发布/下线。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final NoticeService noticeService;

    @Operation(
            summary = "公告列表（分页）",
            description = """
                    两个筛选条件都可选，可以组合：

                    - `status`：`0` 下线/草稿 / `1` 已发布。不传 = 全部
                    - `keyword`：**标题**的模糊匹配（不搜正文 —— 正文是 TEXT，
                      全表扫描的代价和它的收益不成比例）

                    ★ 排序是 **状态升序 + id 倒序**：未发布的在最前面
                    （这是一个待办队列，不是一个档案页），同状态内最新的在前。

                    ★ **返回 `content`（正文全文）**，因为这个列表就是编辑弹窗的
                    数据来源 —— 项目里没有「查单条公告」的接口，也不需要有。

                    ★ 每行都带 `publishTime`：草稿是 `null`，
                    此时 JSON 里这个 key 会**整个消失**
                    （全局 `default-property-inclusion: non_null`），
                    前端判空要写 `row.publishTime` 而不是 `=== null`。""")
    @GetMapping
    public Result<PageResult<NoticeVO>> list(
            @Parameter(description = "状态：0下线 1发布，不传=全部")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "标题关键词")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(noticeService.adminPage(status, keyword, page, size));
    }

    @Operation(
            summary = "新增公告",
            description = """
                    `{"title": "系统维护通知", "content": "……", "type": 1, "status": 1}`

                    - `title` **必填**，≤200 字
                    - `content` **必填**，≤5000 字
                    - `type` 选填，默认 `1`：`1` 公告 / `2` 通知
                    - `status` 选填，默认 `1`：`1` 直接发布 / `0` 存草稿

                    ★ **可以直接建成已发布** —— 运营写一句话就想发出去是常态，
                    不该逼他先存草稿再点一次发布。

                    ★ **没有任何「标题不能重复」的限制**：
                    「系统维护通知」一年要发好几次，重复是正常的。

                    ★ `publishTime` **不在入参里**，由后端盖当前时间。
                    返回体里也没有它 —— 需要它请重新拉一次列表。""")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody NoticeSaveDTO dto) {
        Long id = noticeService.create(dto);

        /*
         * ★ 提示语跟着 status 走，因为这是两个后果完全不同的结果：
         *   发布 → 读者端首页【立刻】出现（管理员要预期到这一点）
         *   草稿 → 读者端什么都看不到（不说的话他会以为没保存成功）
         *
         * ★ 这里可以直接用 == 1 而不用判 null：DTO 上的 @NotNull 已经保证
         *   status 非空（不传时是字段默认值 1）。理由见 NoticeSaveDTO 的类注释 ——
         *   那不是形式主义，它堵的是「已发布但 publish_time 为 NULL」这个坏状态。
         */
        boolean published = dto.getStatus() == 1;
        return Result.success(published
                ? "公告已发布，读者端首页立刻可见"
                : "公告已保存为未发布，读者端看不到", id);
    }

    @Operation(
            summary = "编辑公告 / 发布 / 下线",
            description = """
                    **同一个接口承担三件事**：改内容、发布、下线。
                    请求体是**全量覆盖**（除了 `publishTime`），
                    行内的「发布 / 下线」按钮就是把 `status` 翻一下、其余字段原样带上。

                    ★★ **`publishTime` 的行为**（前端不用管，但值得知道）：

                    | 这次的操作 | publishTime |
                    |---|---|
                    | 草稿 → 发布 | 盖**当前**时间 |
                    | 发布 → 下线 | **保留**原时间（不清空） |
                    | 下线 → **再**发布 | 盖**当前**时间（公告重新置顶） |
                    | 已发布 → 只改错别字 | **不动**（不会把它顶到首页最上面） |

                    ★ 公告不存在返回 `7001`。""")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody NoticeSaveDTO dto) {
        noticeService.update(id, dto);
        return Result.success("保存成功", null);
    }

    @Operation(
            summary = "删除公告",
            description = """
                    逻辑删除。删掉之后读者端立刻看不到它。

                    ★ **「只想让它暂时不出现」应该用「下线」（`PUT` 把 `status` 改成 `0`），
                    不是删除**：下线保留了数据、还能再发布，删掉就得重建一条。
                    这件事由前端的二次确认弹窗讲清楚。

                    ★ **删一条已经不存在的公告返回 `7001`「公告不存在」**，
                    不静默成功 —— 那多半意味着「别人先删了、你的列表还是旧的」，
                    如实说出来比返一个成功更有用。""")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noticeService.delete(id);
        return Result.success("已删除", null);
    }
}
