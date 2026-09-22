package com.novel.modules.interaction.controller;

import com.novel.common.enums.CommentStatus;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.interaction.dto.CommentAuditDTO;
import com.novel.modules.interaction.service.CommentService;
import com.novel.modules.interaction.vo.CommentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— 评论管理。<b>只有列表和审核两个接口。</b>
 *
 * <p><b>★ 这个类【可以】有类级 {@code @RequestMapping}，和读者侧的
 * {@code ReaderCommentController} 不同</b> —— 不是风格不一致，是路径形状不同：
 * 管理端的两个接口天然同族（都在 {@code /api/admin/comments} 下），
 * 读者侧那四个跨了 {@code novels} / {@code comments} 两族。
 *
 * <p><b>★★ 权限：不需要改 {@code SecurityConfig}。</b>
 * {@code .requestMatchers("/api/admin/**").hasRole("ADMIN")} 那条规则
 * 是 P1 就写好的，本类两个接口<b>自动继承</b>。
 * 这正是「按前缀划边界」的收益 —— 代价是<b>任何</b>放在
 * {@code /api/admin/**} 下的路径都自动变成管理员专属，
 * 所以往这个前缀下加接口时，要先确认它确实只有管理员该调。
 *
 * <p><b>★★ 管理端【没有】删除评论的接口，这是刻意的。</b>
 * 管理员的处置动作是<b>隐藏</b>（{@code status = 2}），不是删除：
 * <ul>
 *   <li>隐藏会留下 {@code audit_user_id} / {@code audit_time} / {@code audit_remark}
 *       —— 「谁在什么时候因为什么把它藏了」查得到；删除什么都不留；</li>
 *   <li>隐藏<b>可逆</b>（{@code HIDDEN → VISIBLE} 是合法流转），
 *       删除只能靠改库；</li>
 *   <li>读者侧确实有删除（作者删自己的评论），但那是<b>作者的意愿</b>，
 *       和管理员处置违规内容是两回事。</li>
 * </ul>
 *
 * @author MickeyChen
 */
@Tag(name = "27. 管理端 · 评论管理", description = "评论列表 / 审核。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/comments")
@RequiredArgsConstructor
public class AdminCommentController {

    private final CommentService commentService;

    @Operation(
            summary = "评论列表（分页，含已删除）",
            description = """
                    **需要 ADMIN 角色。**

                    三个筛选条件都可选，不传就是「全部」：

                    | 参数 | 说明 |
                    |---|---|
                    | `status` | `0` 待审 / `1` 已显示 / `2` 已隐藏。**传别的数字会报 `400` 参数错误**，不是静默忽略 |
                    | `novelId` | 只看某本书的评论 |
                    | `keyword` | 正文模糊匹配。`""`（空串）等同于不传 |

                    ★★ **会返回 `deleted = 1` 的评论** —— 这是全项目<b>唯一</b>
                    一个故意不带 `deleted = 0` 的查询。因为管理员需要能回答
                    「昨天那条评论为什么不见了」，如果这里看不到，
                    那读者侧的删除就成了一个没有出口的黑洞。
                    **出参里的 `deleted` 字段必须显示出来**（列表里加一个
                    「已删除」标记），否则管理员会以为看到的是全部评论。

                    ★ **按 `id` 倒序**，理由和读者侧一样（同一秒发的两条按时间排不稳定）。

                    ★ `status` 用**数字**而不是 `PENDING` 这种名字 --
                    同一个字段在 query 里传名字、在 body 里传数字是前端 bug 的温床，
                    所以统一成数字，由这里显式调 `CommentStatus.fromCode` 转换
                    （该方法 `null → null`，于是「不传 = 全部」自然成立）。

                    ★ 出参里的 `status` 是**数字**（`0/1/2`，用来判断），
                    `statusDesc` 是**文案**（「待审核」，用来显示）。
                    显示一律用 `statusDesc`，颜色用
                    `admin-web/src/utils/status.js` 的 `commentStatusType(status)`
                    —— 和 `NovelVO` / `ChapterVO` 是同一条规矩。

                    ★ `nickname` 为空时**整个 key 消失**
                    （全局 `default-property-inclusion: non_null`）。""")
    @GetMapping
    public Result<PageResult<CommentVO>> list(
            @Parameter(description = "状态：0待审 1已显示 2已隐藏，不传=全部")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "作品 id，不传=全部")
            @RequestParam(required = false) Long novelId,
            @Parameter(description = "正文关键字，不传=全部")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        /*
         * ★ 转换放 Controller 而不是让 Service 收 Integer：
         *   Service 的入参类型是 CommentStatus（枚举）——
         *   它的签名就等于「我不接受 3 这种值」，编译期就能拦住。
         *   收 Integer 的话，Service 里每个分支都要记得处理「传了个 3」。
         *
         * ★ fromCode 对未知值是【抛异常】而不是返回 null ——
         *   返回 null 会被上层当成「不筛选」，
         *   于是 ?status=9 静默变成「列出全部」，
         *   前端把筛选条件写错时，看到的是「筛选没生效」而不是报错。
         */
        return Result.success(commentService.adminPage(
                CommentStatus.fromCode(status), novelId, keyword, page, size));
    }

    @Operation(
            summary = "审核评论（通过 / 隐藏）",
            description = """
                    **需要 ADMIN 角色。**

                    `{"status": 1, "remark": "内容合规"}`

                    - `status` **必填**，只接受 `1`（已显示）/ `2`（已隐藏）
                    - `remark` **选填**，≤255 字

                    ★★ **没有「删除」这个选项。** 早先的方案里写过
                    「`0待审 → 1通过 / 2驳回 / 3删除`」—— 那是
                    `CommentStatus` 定稿之前的写法，本项目<b>没有状态 3</b>。
                    传 `3` 会得到 `400` 参数错误。

                    ★ **传 `0` 会被明确拒绝**：「审核结果只能是「已显示」或「已隐藏」，
                    不能改回待审核」。
                    技术上 `canTransitTo` 本来就不允许任何状态回到 `PENDING`
                    （枚举里没有一条边指向它），但那样报出来的会是笼统的
                    「当前评论状态不允许该操作」；这里单独拦一道，
                    是为了让「我手滑点了待审」和「这条评论状态不对」
                    得到不同的提示。

                    **合法流转**（`CommentStatus.canTransitTo`）：

                    | 从 | 能到 |
                    |---|---|
                    | `0` 待审 | `1` 已显示 / `2` 已隐藏 |
                    | `1` 已显示 | `2` 已隐藏 |
                    | `2` 已隐藏 | `1` 已显示 |

                    ★ **重复审核同一条会报 `4005`，不会静默成功。**
                    管理员连点两下时，第二次的意图（「把它设为已显示」）
                    其实已经达成了 —— 但这里仍然报错，和
                    `NovelServiceImpl.transitStatus` 保持一致：
                    静默成功会让「我刚才到底点没点上」变得不可知，
                    而重试一次的成本是零。

                    ★ **可以改判**：已隐藏的能重新设为已显示。
                    误判是审核工作的常态（尤其是「隐藏」这种没有二审的动作），
                    不给回头路的审核系统最后都会被绕过。

                    ★ 每次审核都会往 `audit_log` 写一条
                    （`target_type = 3 评论`，动作取 `APPROVE` / `REJECT`），
                    在「审核日志」页能看到 —— 标题栏显示的是**评论正文的前 30 字**。

                    ★ `remark` 选填的理由：P5 里评论人**没有任何渠道看到
                    审核意见**（没有「我的评论」页面，没有站内信）。
                    强制填写只会得到一堆「违规」这种填了等于没填的内容，
                    而它看起来像一条有效的记录。等 P6 有了评论区的
                    反馈出口，再考虑必填。""")
    @PutMapping("/{id}/status")
    public Result<Void> audit(@Parameter(description = "评论 id") @PathVariable Long id,
                             @Valid @RequestBody CommentAuditDTO dto) {
        commentService.audit(id, dto);
        return Result.success("审核完成", null);
    }
}
