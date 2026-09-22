package com.novel.modules.content.controller;

import com.novel.common.enums.NovelStatus;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.content.dto.NovelCreateDTO;
import com.novel.modules.content.dto.NovelUpdateDTO;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.vo.NovelDetailVO;
import com.novel.modules.content.vo.NovelVO;
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
 * 作者端 —— 作品管理。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code SecurityConfig} 里的
 * {@code /api/author/**} → {@code hasAnyRole("AUTHOR","ADMIN")}），
 * 所以这个类里没有 {@code @PreAuthorize}。
 *
 * <p><b>★★ 但「能进这个门」不等于「能改这本书」。</b>
 * 管理员也有 AUTHOR 之上的权限、也能进这个类，
 * 真正的边界是 Service 里的 {@code requireOwnedNovel} —— 它会对不是自己的作品
 * 返回 {@code 2002}，并打一条 {@code [越权尝试]} 的 WARN。
 * <b>「URL 闸管门，归属校验管物」</b>，两层缺一不可：只留 URL 闸，
 * 任何作者改个 id 就能编辑别人的书。
 *
 * <p><b>★ 这个类里没有一个方法接收 {@code status} 字段</b>，
 * 唯一的改状态入口是 {@code POST /{id}/submit}（业务意图），
 * 而不是 {@code PUT /{id} {"status":2}}。这不是省事，是刻意堵死 ——
 * 详见 {@code NovelService} 的类注释。
 *
 * @author MickeyChen
 */
@Tag(name = "11. 作者端 · 作品", description = "作者的创作管理。★ 需要 AUTHOR 或 ADMIN 角色")
@RestController
@RequestMapping("/api/author/novels")
@RequiredArgsConstructor
public class AuthorNovelController {

    private final NovelService novelService;

    @Operation(
            summary = "我的作品列表",
            description = """
                    **只返回当前登录用户自己的作品**（含草稿、待审核、已驳回 —— 作者要能看到全部）。

                    这个「只看自己的」条件写在 SQL 的 WHERE 里，不是查完再过滤。""")
    @GetMapping
    public Result<PageResult<NovelVO>> myList(
            @Parameter(description = "状态筛选：0草稿 1待审核 2已上架 3已下架 4已驳回。不传=全部")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        /*
         * ★ 这里显式调 NovelStatus.fromCode 而不是把参数声明成 NovelStatus。
         *
         * 声明成 NovelStatus 的话，Spring 会按【枚举名】转换，
         * 于是 ?status=2 会失败、必须写 ?status=PUBLISHED；
         * 而同一个字段在 JSON body 里是数字（@JsonValue 决定）。
         * 「body 传数字、query 传名字」这种一个字段两种写法，
         * 前端一定会有人写错，而写错的表现是 400 而不是「查不到数据」，
         * 排查起来很费劲。统一成数字，转换由我们自己控制。
         *
         * ★ fromCode(null) 返回 null，正好就是「不筛选」的语义，不用额外判断。
         */
        return Result.success(novelService.myPage(NovelStatus.fromCode(status), page, size));
    }

    @Operation(
            summary = "新建作品",
            description = """
                    新建的作品**永远是草稿**（status = 0），由后端写死。

                    `status` 和 `authorId` 都**不能**通过请求体传入 —— DTO 上根本没有这两个字段。
                    否则一个 `{"status": 2}` 就能把书直接建上架，审核流程形同虚设。

                    分类必须是**存在且启用**的，否则返回 `6001`。""")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody NovelCreateDTO dto) {
        return Result.success("新建成功", novelService.create(dto));
    }

    @Operation(
            summary = "作品详情（含草稿）",
            description = """
                    作者查看自己的作品，**目录含全部状态的章节**（草稿章、待审章都在）——
                    这和读者端 `GET /api/novels/{id}` 的口径正好相反。

                    作品不存在返回 `2001`；**不是自己的返回 `2002`**（管理员也会拿到 2002）。""")
    @GetMapping("/{id}")
    public Result<NovelDetailVO> detail(@PathVariable Long id) {
        return Result.success(novelService.authorDetail(id));
    }

    @Operation(
            summary = "编辑作品信息",
            description = """
                    改书名 / 封面 / 分类 / 简介 / 标签 / 是否完结。

                    **状态不允许编辑时返回 `2003`**：待审核期间不能改
                    （否则审核员看的是 A 版、通过的是 B 版），已上架也不能直接改
                    （要改先下架）。能编辑的状态只有 0 草稿 / 3 已下架 / 4 已驳回。

                    ★ 分类**没变**时不校验分类是否可用 —— 否则一个分类被停用后，
                    挂着它的书就再也没法保存任何改动了。""")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody NovelUpdateDTO dto) {
        novelService.update(id, dto);
        return Result.success("保存成功", null);
    }

    @Operation(
            summary = "删除作品",
            description = """
                    **名下有章节时返回 `2005`**，请先删除章节；
                    只是想暂停对外的话，用「下架」而不是删除。

                    逻辑删除，可被管理员找回（P5 回收站）。""")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        novelService.delete(id);
        return Result.success("删除成功", null);
    }

    @Operation(
            summary = "提交审核",
            description = """
                    草稿 / 已驳回 / 已下架 → **待审核**。

                    重新提交时会**清掉上一次的驳回理由** ——
                    不清的话作者会看到「待审核」旁边挂着一句旧意见，以为又被打回了。

                    当前状态不允许提交时返回 `2003`，**提示里会带上「从什么状态到什么状态」**。""")
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        novelService.submit(id);
        return Result.success("已提交审核", null);
    }

    @Operation(
            summary = "撤回提交",
            description = """
                    **待审核 → 草稿**。

                    作者提交后发现简介有错别字，在审核出结果前可以自己把书拿回去改，
                    不用等管理员驳回。

                    ★ **这不绕过审核**：撤回之后状态变回草稿，改完还要重新提交、重新审。
                    审核期间「不能编辑内容」那条限制（`2003`）**依然生效** ——
                    截住审核的是「待审核不可编辑」，不是「待审核不可撤回」。

                    ★ 只有待审核能撤回，其余状态返回 `2003`
                    （已上架的书要改内容，走「下架」——状态机不允许上架直接退草稿）。

                    ★ 撤回会记一条 `REVERT` 流水，和章节那边「已发布改正文自动退回草稿」
                    用的是同一个动作码 —— 两者在流水上表达的是同一件事。""")
    @PostMapping("/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id) {
        novelService.withdraw(id);
        return Result.success("已撤回，可以继续修改了", null);
    }

    /*
     * ★ 章节相关的 6 个接口（/api/author/novels/{novelId}/chapters 等）
     *   在 P2 第 6 步加，见 AuthorChapterController。这里先不预埋空方法 ——
     *   一个返回 501 或空列表的桩会让人以为「章节模块做好了但没数据」。
     */
}
