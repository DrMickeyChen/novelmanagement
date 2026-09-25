package com.novel.modules.ai.controller;

import com.novel.common.result.Result;
import com.novel.modules.ai.dto.SensitiveWordSaveDTO;
import com.novel.modules.ai.dto.SensitiveWordScanDTO;
import com.novel.modules.ai.service.SensitiveWordScanner;
import com.novel.modules.ai.service.SensitiveWordService;
import com.novel.modules.ai.vo.SensitiveWordScanVO;
import com.novel.modules.ai.vo.SensitiveWordStatusVO;
import com.novel.modules.ai.vo.SensitiveWordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 —— AI 违禁词库（E1/S7）。
 *
 * <p><b>★ 访问控制由 URL 前缀统一兜住，这个类里一个 {@code @PreAuthorize} 都不需要。</b>
 * {@code ContentSecurityConfig} 里已有 {@code requestMatchers("/api/admin/**").hasRole("ADMIN")}。
 * 同 {@code AdminAiPromptController} / {@code AdminCategoryController}。
 *
 * <p>★ 路由不冲突：{@code /api/admin/ai/**} 不与 auth 的五个前缀、
 * interaction 的 {@code /api/admin/comments}、search 的 {@code /api/admin/search} 重叠，
 * 全部落在网关给 content 的那条 {@code order: 5} 上 —— <b>E1 对网关是零改动。</b>
 *
 * <h3>★★★ 这个类里最该读懂的是「写」与「生效」被拆成了两组端点</h3>
 *
 * <pre>
 *   POST/PUT  /word/**        →  改库（「库里变了」）
 *   POST      /word/reload    →  重建快照（「判定变了」）
 *   GET       /word/status    →  把两者差多少摆出来
 * </pre>
 * ★★ 这三组<b>不能合并</b>。合并（保存后自动 reload）会让「快照真的被替换过」
 * 这件事<b>无法证明</b>：既然每次保存都换，那「生效」就与保存变成了同一件事，
 * 再也分不出「它生效是因为我保存了」还是「因为它本来就该生效」。
 * S7 的第三条阳性对照（先断言未命中、reload 后命中）依赖的正是这个中间态。
 *
 * <h3>★★ 这个类里的所有「失败」都返 HTTP 200 + 业务码</h3>
 *
 * <p>与 S6 的 {@code AiPreflight} 相反，而这是<b>对的</b>：
 * {@code AiPreflight} 那条链路上，200 会被 {@code sse.js} 渲染成
 * 「请检查代理配置」这句指向别的系统的假话；而这里是<b>管理端的 JSON 接口</b>，
 * 前端 {@code request.js} 对非 200 业务码的处理是
 * {@code ElMessage.error(res.message)} —— <b>文案原样显示给管理员，一个字都不丢。</b>
 * ★ 判据是「这个响应会不会被某个渲染层翻译成别的话」，不是「状态码对不对」。
 *
 * @author MickeyChen
 */
@Tag(name = "22. 管理端 · AI 违禁词库", description = "词库增改查 + 试扫 + 快照重载。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/ai/word")
@RequiredArgsConstructor
public class AdminAiWordController {

    private final SensitiveWordService sensitiveWordService;
    private final SensitiveWordScanner sensitiveWordScanner;

    @Operation(
            summary = "词库列表",
            description = """
                    不传 `enabled` 返回全部，按 等级倒序 → id 升序（运营最先要看的是高危那几条）。

                    **包含已停用的词** —— 过滤掉它们，停用就变成一个改不回来的操作。""")
    @GetMapping
    public Result<List<SensitiveWordVO>> list(
            @Parameter(description = "1 只看启用的，0 只看停用的，不传返回全部")
            @RequestParam(required = false) Integer enabled) {
        return Result.success(sensitiveWordService.list(enabled));
    }

    @Operation(
            summary = "快照状态",
            description = """
                    把「库里有多少条」和「**快照里有多少条**」并排返回 ——
                    两个数不相等就说明有人改了词但还没重载。

                    - `loaded` = 快照是否存在（false = 从没加载成功过，查 DB）
                    - `ready` = **唯一允许放行的状态**，为 false 时所有审核会被拒
                    - `dbUnavailable` = true 时 `enabledRows`/`totalRows` 是 **null 而不是 0**
                      （「读不到」不是「一条都没有」）

                    ★ `version` 每次成功重载 +1，可以用来确认「刚才那次重载真的换了快照」。""")
    @GetMapping("/status")
    public Result<SensitiveWordStatusVO> status() {
        return Result.success(sensitiveWordScanner.status());
    }

    @Operation(
            summary = "立即重载快照",
            description = """
                    **改完词必须调它，改动才会生效**（或等最多 10 分钟的定时兜底）。

                    保存接口**刻意不自动重载**：自动重载会让「这次改动到底生效了没有」
                    变得无法验证 —— 每次保存都换快照，就分不出「生效」和「本来就该生效」。

                    ★ 重载失败**不会**清掉现有快照（旧快照继续用，只是滞后），
                    返回值里的 `lastError` 会说明失败原因。
                    ★ 它**不会**报错 —— 失败也返回 200，因为「重载失败」不是一个请求级别的错误，
                    而是一个要在状态里被看见的事实。""")
    @PostMapping("/reload")
    public Result<SensitiveWordStatusVO> reload() {
        return Result.success("已重载", sensitiveWordScanner.reload());
    }

    @Operation(
            summary = "试扫一段文本",
            description = """
                    拿一段文本过一遍**当前的快照**。只读：不写表、不扣配额、不调模型。

                    - `maxLevel` = 命中的最高等级（`0` = 干净）
                    - `hits` = 命中的词，同一词出现多次只算一条
                    - `wordCount` / `version` = 本次判定依据的是哪一份快照

                    ★ 它是**读快照**不是读库：刚保存的词**重载之前扫不到**。

                    失败语义：

                    | 情况 | HTTP | code |
                    |---|---|---|
                    | 词库快照不存在（从没加载成功） | 200 | `5007` |
                    | 词库存在但**一条启用的词都没有** | 200 | `5007` |

                    ★★ 这两档是 **fail-closed**，是刻意的：词库是「参与筛选的输入」，
                    它不可用时**绝不能**返回一个漂亮的「命中 0 条」——
                    那等于把「我没在筛」渲染成了「这段文字是干净的」。""")
    @PostMapping("/scan")
    public Result<SensitiveWordScanVO> scan(@Valid @RequestBody SensitiveWordScanDTO dto) {
        return Result.success(sensitiveWordScanner.scan(dto.getText()));
    }

    @Operation(
            summary = "新增违禁词",
            description = """
                    两端空白会被去掉；词重复返回 `5008`。

                    ★ 新增之后**需要重载**才会命中。""")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody SensitiveWordSaveDTO dto) {
        return Result.success("新增成功", sensitiveWordService.create(dto));
    }

    @Operation(
            summary = "编辑违禁词",
            description = """
                    校验规则同新增。词不存在返回 `5009`，与别的词重复返回 `5008`。

                    ★ 改错字走**编辑**，不要「新增一条再停用旧的」——
                    后者会让词库里堆一排停用的错字。""")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SensitiveWordSaveDTO dto) {
        sensitiveWordService.update(id, dto);
        return Result.success("修改成功", null);
    }

    @Operation(
            summary = "启用 / 停用",
            description = """
                    只改 `enabled` 一列，**不会**覆盖这一行的其它字段。

                    ★ 单独开一个端点而不是让前端发完整的 PUT：列表页那个开关只带
                    「id + 目标值」。发一整行的话，前端必须先读再原样回传，
                    两次读之间别人改了这条词，这次「只是关一下开关」的请求
                    会把别人的修改一并覆盖回去，**而且不报任何错**。

                    ★ 值没变时直接返回成功（幂等），不报错。""")
    @PutMapping("/{id}/enabled")
    public Result<Void> updateEnabled(
            @PathVariable Long id,
            @Parameter(description = "1 启用，0 停用", required = true) @RequestParam Integer enabled) {
        sensitiveWordService.updateEnabled(id, enabled);
        return Result.success(enabled != null && enabled == 1 ? "已启用" : "已停用", null);
    }
}
