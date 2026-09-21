package com.novel.modules.system.controller;

import com.novel.common.enums.AuditStatus;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.system.dto.AuthorApplyAuditDTO;
import com.novel.modules.system.service.AuthorApplyService;
import com.novel.modules.system.vo.AuthorApplyVO;
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
 * 管理端 —— 作者申请审核。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 这个类里没有 {@code @PreAuthorize}，也不用在 {@code SecurityConfig} 里登记。
 *
 * <p><b>★★ 「通过」不是盖个章，是真的给权限。</b>
 * 通过时会往 {@code sys_user_role} 插一行 AUTHOR，并把申请人的在线会话清掉
 * （{@code tokenStore.removeAll}）—— 后者是必须的：角色和权限是<b>登录时</b>
 * 写进 Redis 的，不踢的话申请人重新打开创作中心照样被拒，
 * 而他看到的提示是「你还不是作者」。
 * <p>所以前端在通过之后<b>必须</b>告诉管理员「该用户已获得作者权限，
 * 需要重新登录才生效」—— 不说的话，管理员会以为没生效而再点一次。
 *
 * <p><b>★ 读者侧提交申请的接口【不在这里】，在 {@code ReaderAuthorApplyController}。</b>
 * 理由见那个类的注释（一句话：提交申请的人恰恰还不是作者）。
 *
 * @author MickeyChen
 */
@Tag(name = "28. 管理端 · 作者申请", description = "申请列表 / 通过（授予 AUTHOR 角色）/ 驳回。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/author-applies")
@RequiredArgsConstructor
public class AdminAuthorApplyController {

    private final AuthorApplyService authorApplyService;

    @Operation(
            summary = "作者申请列表（分页）",
            description = """
                    两个筛选条件都可选，可以组合：

                    - `status`：`0` 待审 / `1` 已通过 / `2` 已驳回。不传=全部。
                      **传别的数字会报 `400` 参数错误**，不是静默忽略
                    - `keyword`：**笔名、申请人用户名或昵称**的模糊匹配

                    ★ 排序是 **状态升序 + id 倒序**：待审的在最前面（这是一个
                    待办队列，不是一个档案页），同状态内最新提交的在前。

                    ★ 每行带 `username`（登录名）和 `nickname`（昵称）。
                    **`nickname` 为空说明申请人已注销**，此时 JSON 里这个 key
                    会整个消失（全局 `default-property-inclusion: non_null`）。

                    ★ 结果**不含** `auditUserId`（审核人 id）—— 要看「谁审的、
                    什么时候审的」去审核日志页，那里有操作人昵称。""")
    @GetMapping
    public Result<PageResult<AuthorApplyVO>> list(
            @Parameter(description = "状态：0待审 1已通过 2已驳回，不传=全部")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "笔名 / 申请人用户名 / 昵称 关键词")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        /*
         * ★ 转换放 Controller 而不是让 Service 收 Integer：
         *   Service 的入参类型是 AuditStatus（枚举）—— 它的签名就等于
         *   「我不接受 3 这种值」，编译期就能拦住。
         *   fromCode 对未知值是【抛异常】而不是返回 null，
         *   否则 ?status=9 会静默变成「列出全部」，
         *   前端把筛选写错时看到的是「筛选没生效」而不是报错。
         */
        return Result.success(authorApplyService.adminPage(
                AuditStatus.fromCode(status), keyword, page, size));
    }

    @Operation(
            summary = "审核作者申请",
            description = """
                    **需要 ADMIN 角色。**

                    `{"pass": true, "remark": ""}`

                    - `pass` **必填**：`true` 通过，`false` 驳回
                    - `remark` 选填，≤255 字。**驳回时是给申请人看的理由**
                      （读者端 `GET /api/reader/author-apply/mine` 会返回它）

                    ★★ **通过 = 两件事**：改申请状态 **+ 给这个人 AUTHOR 角色**。
                    只改状态的话，用户会看到「已通过」但仍然进不去创作中心
                    （`SecurityConfig` 的 `hasAnyRole("AUTHOR","ADMIN")` 会拦下），
                    而两个界面看起来都正常。

                    ★★ **通过后申请人会被强制下线**（角色变了，Redis 里的
                    权限是旧的）。他重新登录后才能进创作中心。
                    前端要提示「该用户需重新登录才生效」。

                    ★ **重复提交同一个结果不会报错**（幂等）——
                    管理员连点两下是常态，第二次直接返回成功。
                    但**把「已驳回」改成「已通过」会被拒绝**（返回 `1016`）：
                    终态之间没有边，要重来请让申请人重新提交一份。

                    ★ 申请不存在返回 `1014`。""")
    @PutMapping("/{id}/audit")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody AuthorApplyAuditDTO dto) {
        authorApplyService.audit(id, dto);
        return Result.success(Boolean.TRUE.equals(dto.getPass())
                ? "已通过，该用户已获得作者权限，需重新登录后生效"
                : "已驳回，申请人可以看到这条理由并重新提交", null);
    }
}
