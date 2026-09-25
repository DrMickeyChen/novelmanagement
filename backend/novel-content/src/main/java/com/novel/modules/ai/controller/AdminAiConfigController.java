package com.novel.modules.ai.controller;

import com.novel.common.result.Result;
import com.novel.modules.ai.dto.AiConfigSaveDTO;
import com.novel.modules.ai.service.AiConfigService;
import com.novel.modules.ai.vo.AiConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— AI 配置（E1）。
 *
 * <p><b>★ 访问控制由 URL 前缀统一兜住，这个类里一个 {@code @PreAuthorize} 都不需要</b>
 * （{@code ContentSecurityConfig} 已有 {@code /api/admin/**} → {@code hasRole("ADMIN")}）。
 * 同 {@code AdminCategoryController} / {@code AdminAiPromptController}。
 *
 * <p>★ 路由不冲突：{@code /api/admin/ai/**} 不与 auth 的五个前缀、interaction 的
 * {@code /api/admin/comments}、search 的 {@code /api/admin/search} 重叠 ——
 * 全部落在网关给 content 的那条 {@code order: 5} 上，<b>E1 对网关零改动</b>。
 *
 * <h3>★★★ 这是全项目唯一一个「写进去的东西永远不会被读回来」的接口</h3>
 *
 * <p>{@code apiKey} 只写不读：{@link #get()} 返回的是掩码 {@code sk-****abcd}，
 * 前端拿不到明文，<b>浏览器里、localStorage 里、Network 面板里都不会出现它</b>。
 * 这条不是「顺手做一下」，它是 E1 风险清单 R1（Key 泄漏）的第一道处置。
 *
 * <p>★★ <b>连带的 UI 义务</b>：既然读不回来，管理端那个输入框<b>在保存成功后必须清空</b>，
 * 而且<b>不能回显刚才输入的值</b> —— 回显会让「页面上有一个像 Key 的东西」变成常态，
 * 而下一次截图、录屏、共享屏幕时就带出去了。见 S5 的验收项。
 *
 * <h3>★ 为什么没有 DELETE</h3>
 *
 * <p>配置行是种子数据的一部分（{@code id=1}），删掉它 {@code currentRow()} 会直接 500。
 * 想「停用」就 {@code enabled=0}；想「清掉 Key」就 {@code clearApiKey=true} ——
 * 两者都是可逆的，而删除不是。
 *
 * @author MickeyChen
 */
@Tag(name = "22. 管理端 · AI 配置", description = "模型参数 / API Key / 启停。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/ai/config")
@RequiredArgsConstructor
public class AdminAiConfigController {

    private final AiConfigService aiConfigService;

    @Operation(
            summary = "读取 AI 配置",
            description = """
                    **永远不返回 API Key**，只返回掩码 `sk-****abcd`（长度固定，不泄露真实长度）。

                    响应里有**两个** provider，它们**不是一回事**：

                    | 字段 | 来源 | 含义 |
                    |---|---|---|
                    | `provider` | 数据库那一行 | 记录的厂商名，恒为 `DEEPSEEK`，**只读** |
                    | `effectiveProvider` | YAML `novel.ai.provider` | **实际生效的实现**：`mock` / `deepseek` |

                    ★ `effectiveProvider` 才是「AI 面板背后是真模型还是演示数据」的答案。
                    页面应当据此显示横幅：**当前后端配置为 mock，此处填写的 Key 不会生效** ——
                    否则「填了 Key 却不生效」是**静默**的，管理员会以为自己填错了然后反复重填。

                    ★ 若服务端的 `AI_KEY_SECRET` 与录入 Key 时不一致，那把 Key 就**解不开**。
                    此时**不是 500**，而是：`apiKeyConfigured = true`、`apiKeyMasked = null`、
                    **`apiKeyNotice` 带一句人话** —— 前端**原样显示 `apiKeyNotice`** 即可。

                    ★★ 为什么不是 500（**这是验收实测改出来的一版**）：本接口的响应体
                    和「保存」接口是同一个 VO，而**保存时也会走到解密**。
                    返 500 的症状是：管理员改温度 → **库里改成功了** → 界面报「保存失败」→ 他重试，
                    每次再写一遍。**「真成功被报成失败」与「假成功」的后果一样：人都做了错误的下一步。**
                    而带一句 `apiKeyNotice` 还有个好处：**不是死胡同** ——
                    页面照常加载，管理员看得到 `baseUrl`/`model`，重新录入一次 Key 就能修好。
                    （加密层 `AiCrypto` 仍然抛异常，一个字没改：那是「绝不把乱码当 Key 用」的落点。）""")
    @GetMapping
    public Result<AiConfigVO> get() {
        return Result.success(aiConfigService.get());
    }

    @Operation(
            summary = "保存 AI 配置",
            description = """
                    **`apiKey` 只写不读**，三条规则：

                    | 传法 | 行为 |
                    |---|---|
                    | 不传 / 空串 | **保持库里那把不变** |
                    | 传了新值 | 加密后覆盖 |
                    | `clearApiKey=true` | 清空（之后真实调用返回 `5002`） |

                    ★ 第一条是关键：读接口不返回 Key，所以输入框打开时是**空的**。
                    若把「空」当成「清空」，管理员**只想改一下温度**顺手保存就会把 Key 抹掉，
                    而且**界面上看不出任何变化**（框本来就是空的），
                    直到下一次作者点生成才会发现 —— 中间可能隔着很多天。

                    ★ 两个同时传是自相矛盾的请求，返回 `400`（不猜用户想要哪个）。

                    `provider` **不在入参里**：数据库那一列恒为 `DEEPSEEK`，且它不决定哪个实现在跑，
                    让它可写就会造出一个「改了却不生效」的假旋钮。""")
    @PutMapping
    public Result<AiConfigVO> save(@Valid @RequestBody AiConfigSaveDTO dto) {
        return Result.success("保存成功", aiConfigService.save(dto));
    }
}
