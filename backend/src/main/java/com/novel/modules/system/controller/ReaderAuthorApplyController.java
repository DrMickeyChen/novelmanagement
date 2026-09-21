package com.novel.modules.system.controller;

import com.novel.common.result.Result;
import com.novel.modules.system.dto.AuthorApplyCreateDTO;
import com.novel.modules.system.service.AuthorApplyService;
import com.novel.modules.system.vo.AuthorApplyVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者端 —— 申请成为作者。
 *
 * <p><b>★★ 路径是 {@code /api/reader/author-apply}，不是 {@code /api/author-applies}。</b>
 * 后者虽然也能跑，但是个陷阱：{@code SecurityConfig} 里
 * {@code /api/author/**} 是 {@code hasAnyRole("AUTHOR","ADMIN")}，
 * 而 Ant 匹配下 {@code /api/author/**} <b>不会</b>匹配 {@code /api/author-applies}
 * （{@code /**} 要求 {@code /} 后跟任意段，而 {@code -applies} 不是 {@code /}）——
 * 所以它今天是安全的。
 * <p>但读代码的人一眼会把 {@code /api/author-applies} 看成「作者接口」，
 * 而这个接口的使用者<b>恰恰还不是作者</b>（是 READER）。
 * 将来有人「整理」{@code SecurityConfig} 时把它收进 {@code /api/author/**}，
 * 就会静默变成「非作者不能申请成为作者」—— <b>逻辑死锁，谁也进不来</b>。
 * <p>{@code /api/reader/**} 的语义是对的（这是读者侧接口），
 * 和 {@code ReaderNovelController} / {@code ReaderHistoryController} 同族，
 * 而且它落在 {@code anyRequest().authenticated()} 里 ——
 * <b>不用改 {@code SecurityConfig}，任何登录用户都能调</b>。
 *
 * @author MickeyChen
 */
@Tag(name = "29. 读者端 · 作者申请", description = "提交申请 / 查自己的申请进度。★ 需要登录")
@RestController
@RequestMapping("/api/reader/author-apply")
@RequiredArgsConstructor
public class ReaderAuthorApplyController {

    private final AuthorApplyService authorApplyService;

    @Operation(
            summary = "提交成为作者的申请",
            description = """
                    `{"penName": "墨白", "intro": "写过两本都市文"}`

                    - `penName` **必填**，≤50 字
                    - `intro` 选填，≤500 字

                    ★ 申请人从登录态取，**入参里没有 userId** ——
                    让它成为参数就等于给了「替别人申请」的能力，
                    而通过会给**那个人**发 AUTHOR 角色。

                    ★ **已经是作者**（有 AUTHOR 角色）会返回 `1015`。
                    ★ **已经有一份在审核中**会返回 `1009`，不产生第二条记录。
                    ★ **被驳回之后可以重新提交**（那正是驳回的用途），
                    新提交的是一份新记录，旧的那份留着作为历史。

                    ★ 提交**不写审核流水**：`audit_log` 记的是状态流转，
                    而申请一出生就是「待审核」，没有流转可记
                    （对照作品：先建草稿、再提交审核，那才是一次流转）。""")
    @PostMapping
    public Result<Long> submit(@Valid @RequestBody AuthorApplyCreateDTO dto) {
        return Result.success("申请已提交，请等待管理员审核", authorApplyService.submit(dto));
    }

    @Operation(
            summary = "查我最近一次的申请",
            description = """
                    **需要登录。**

                    ★ **从来没申请过时 `data` 为 `null`** —— 这不是错误，
                    前端据此显示「申请成为作者」的表单。
                    注意全局 `default-property-inclusion: non_null` 会让
                    `data` 这个 key 整个消失，取值写 `res.data ?? null`。

                    ★ 返回的是**最近一份**（被驳回后重新提交会产生第二条记录）。
                    全部历史在管理端的列表里。

                    ★ **这个接口不回答「我是不是作者」** ——
                    那个信息在 `/api/auth/me` 的 `roles` 里，前端也已经在
                    `stores/user.js` 里存着了。边界情况（管理员手工加了 AUTHOR、
                    本人从没申请过）在这里返回 `null`，提交时后端会返回 `1015`。

                    ★ `auditRemark` 会返回给申请人，**它是驳回理由**——
                    这一点和评论的 `auditRemark`（管理员自留备注、读者看不到）
                    正好相反，别照抄评论那边的注释。""")
    @GetMapping("/mine")
    public Result<AuthorApplyVO> mine() {
        return Result.success(authorApplyService.mine());
    }
}
