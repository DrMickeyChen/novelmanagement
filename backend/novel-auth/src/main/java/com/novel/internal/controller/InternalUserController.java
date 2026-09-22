package com.novel.internal.controller;

import com.novel.common.result.Result;
import com.novel.internal.service.InternalUserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * <b>供兄弟服务（M1 起是 content，M2 起 interaction 也来）读取用户信息的内部接口。</b>
 *
 * <p>M1 新增。它存在的原因是「数据在 auth 这儿，而 content 要用」——
 * 改造前那是一句同进程的 {@code sysUserMapper.selectById()}，
 * 拆服务之后必须变成一次 HTTP 调用。
 *
 * <p><b>★★ 这个类只有三行方法体，全部逻辑在
 * {@link InternalUserQueryService} —— 这是刻意的。</b>
 * Controller 的职责是「把 HTTP 翻译成方法调用」，
 * 那些查询里带着的取舍（条数上限、单列投影、{@code selectByIds}）
 * 属于数据访问，放这里会在下一次改接口时被顺手删掉。
 *
 * <h3>★ 为什么路径是 {@code /internal/**} 而不是 {@code /api/**}</h3>
 *
 * <p>因为 {@code /internal/**} <b>刻意不在 {@code /api/**} 之下</b>，
 * 而网关的路由表里只有 {@code /api/**} 和 {@code /uploads/**} 两类前缀
 * —— 于是<b>网关在结构上就不可能把这个接口转发出去</b>。
 *
 * <p>这不是「记得别配路由」那种纪律，而是「配不出这条路由」：
 * 公网拿不到它，和有没有人犯错无关。这是它敢用
 * 「一个共享密钥 + 不认匿名」这种轻量防护的前提
 * （详见 {@code HeaderAuthenticationFilter} 的类注释）。
 *
 * <h3>★ 为什么参数里没有「谁在调我」</h3>
 *
 * <p>因为这几个接口是<b>只读的</b>，而且返回的是「谁都能看到的信息」
 * （作者昵称、笔名 —— 首页匿名访客本来就看得见）。
 * 所以它们不需要知道调用者是谁，也就没有伪造身份的问题。
 * <p>对照 {@code /internal/author-apply-audit-logs}（在 novel-content 那边）
 * ——那是个<b>写</b>接口，流水要记操作人，所以那边必须把身份传过去。
 * <b>只读内部接口不需要身份，写内部接口需要</b>，这条区别是有意的。
 *
 * <h3>★ 这些接口会被【匿名请求】间接触发</h3>
 *
 * <p>{@code /internal/users/nicknames} 会在<b>首页书列表</b>被调用，
 * 而首页是允许匿名访问的（产品硬要求）。那一刻网关转发的是
 * <b>一个没有身份、只有共享密钥</b>的请求。
 * <p>所以这些端点在 {@code AuthSecurityConfig} 里必须是 {@code permitAll()}
 * —— <b>不能</b>设成 {@code hasRole("ADMIN")} 之类，否则匿名首页直接 500。
 * 把关的是共享密钥那一层（见 {@code HeaderAuthenticationFilter}），
 * 不是 Spring Security 的角色闸。
 *
 * @author MickeyChen
 */
@Slf4j
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final InternalUserQueryService queryService;

    /**
     * 单个用户的昵称。用于小说详情页那种「一条记录配一个作者名」的场景。
     *
     * <p>★ 用户不存在（或已注销）时 {@code data} 是 {@code null}，<b>不是 404</b>：
     * 「查不到」在这里是正常结果，不是一个错误。
     */
    @GetMapping("/{id}/nickname")
    public Result<String> nickname(@PathVariable Long id) {
        return Result.success(queryService.nicknameOf(id));
    }

    /**
     * 批量取昵称，返回 {@code {userId: nickname}}。
     *
     * <p><b>★ 列表接口走这一个，不要循环调上面那个。</b>
     * 一页 20 本小说就是 20 次跨进程调用 —— 这是 M1 之后 N+1 的新形态
     * （从 20 次 SQL 变成 20 次 HTTP），代价更高。
     *
     * <p>★ 用 {@code POST} 而不是 {@code GET}：id 列表可能很长（一页 20 个，
     * 加上搜索页的 100 个），塞进 query string 会撞上 URL 长度限制，
     * 而那个限制在不同服务器上不一样（症状是「书多了就报 400」，
     * 本地还复现不了）。POST + body 没有这个问题。
     *
     * <p>★ {@code required = false}：空集合是合法输入（返回空 Map），
     * 让调用方不必为「这一页一个 id 都没有」写一个特判。
     */
    @PostMapping("/nicknames")
    public Result<Map<Long, String>> nicknames(@RequestBody(required = false) List<Long> ids) {
        return Result.success(queryService.nicknamesOf(ids));
    }

    /**
     * 反查：昵称关键词 → 用户 id 列表。给「按作者名搜索作品」用。
     *
     * <p>★ 关键词为空时返回<b>空列表</b>而不是全部用户 —— 见
     * {@code InternalUserQueryService#userIdsByNickname} 的判空。
     */
    @GetMapping("/ids-by-nickname")
    public Result<List<Long>> idsByNickname(@RequestParam String keyword) {
        return Result.success(queryService.userIdsByNickname(keyword));
    }
}
