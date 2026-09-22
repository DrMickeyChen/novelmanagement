package com.novel.internal.controller;

import com.novel.common.internal.AuthorApplyBriefDTO;
import com.novel.common.result.Result;
import com.novel.internal.service.InternalUserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <b>作者申请的内部读接口（M1）。</b>
 *
 * <p>只有一条路径，所以它本可以和 {@link InternalUserController} 合成一个类 ——
 * 分开写是因为<b>路径前缀不同</b>（{@code /internal/author-applies}
 * 和 {@code /internal/users}），而 {@code @RequestMapping} 是类级的。
 * 硬塞在一起的话得给每个方法写全路径，那才是真的容易写错。
 *
 * <p><b>★★ 这条接口是「一个已经有前科的坑」的另一半，看之前请先看
 * {@code ContentUserLookup#authorApplyTitlesOf} 的注释：</b>
 * {@code audit_log.target_id} 对作者申请存的是<b>申请表的 id</b>，
 * 不是申请人的 {@code user_id}。拿申请 id 去当用户 id 查昵称
 * <b>不会报错</b> —— 它会查到<b>另一个真实存在的人</b>，
 * 于是日志页上显示的是别人的申请。
 *
 * <p>所以这里返回的 {@link AuthorApplyBriefDTO} <b>必须同时带上
 * {@code id}、{@code userId}、{@code penName} 三个字段</b>：
 * 两个 id 都在，调用方才有可能走对「申请 → 人」那两跳。
 * 少给 {@code userId}，调用方就只能猜，而猜错的方式正是上面那个不报错的错。
 *
 * @author MickeyChen
 */
@Slf4j
@RestController
@RequestMapping("/internal/author-applies")
@RequiredArgsConstructor
public class InternalAuthorApplyController {

    private final InternalUserQueryService queryService;

    /**
     * 批量取申请的最少信息（id / 申请人 / 笔名）。
     *
     * <p>★ 查不到的 id <b>不会出现在结果里</b>（申请被逻辑删除，或 id 本就不存在）
     * —— 调用方 {@code get} 出 null，按「已删除」处理。
     * 这和作品 / 章节 / 评论的处理口径完全一致，
     * 见 {@code AuditLogVO#targetTitle}。
     */
    @PostMapping("/briefs")
    public Result<List<AuthorApplyBriefDTO>> briefs(@RequestBody(required = false) List<Long> ids) {
        return Result.success(queryService.briefsOf(ids));
    }
}
