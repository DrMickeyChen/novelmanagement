package com.novel.search.controller;

import com.novel.common.result.Result;
import com.novel.search.service.IndexRebuildService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>管理端 —— 搜索索引的运维端点（M4 / S8）。</b>只有 ADMIN 能碰。
 *
 * <p>★ 路径 {@code /api/admin/search/**}。<b>它在网关那边有一条 order &lt; 5 的独立路由</b>
 * —— 因为 content 也占着 {@code /api/admin/**}（order 5）。
 * ★★ 漏配那条路由的症状是<b>静默 404</b>：请求被 content 抢走，而 content 没有这个接口。
 * 完整的路由不变式见 {@code novel-gateway/application.yml}（S9）。
 *
 * <h3>★★ 为什么这个端点必须存在 —— 它把「小时级」变成「秒级」</h3>
 *
 * <p>定时全量重建的周期是 <b>6 小时</b>（{@code IndexRebuildJob}）。
 * 那个周期是给「消息丢了，慢慢自愈」用的，它<b>不能</b>用来回答
 * 「我现在就想确认索引是对的」——
 * 一次验收、一次线上排障、一次改完索引设计后的复验，
 * 都不可能等 6 小时。
 *
 * <p>★★ <b>更重要的是：它是「索引自愈」这条性质的唯一可测入口。</b>
 * 验收里那条「手工把 ES 里某条文档改坏 → 一个重建周期内自愈」，
 * 如果没有本端点，就只能<b>改系统时间或者干等</b> ——
 * 而一个验不了的性质等于不存在（铁律 8）。
 * <b>本端点的意义不是「方便」，是「让那条性质可以被证伪」。</b>
 *
 * <h3>★★ 它是同步的，而且这是刻意选的</h3>
 *
 * <p>请求会一直等到重建跑完，然后把条数返回给管理员。
 * <ul>
 *   <li><b>好处</b>：调用方拿到的是一个<b>确定的结果</b>（灌了几条、花了多久），
 *       而不是「已提交，请稍后自己去看日志」——后者在排障时几乎没用；</li>
 *   <li>★★ <b>代价如实写</b>：库大到重建超过网关/容器的读超时（30~60 秒）时，
 *       管理员会看到超时，<b>而重建其实还在跑</b>。
 *       以本项目的量级（几十本）不会发生，但它是一条<b>会随数据量失效</b>的设计，
 *       所以记为 M5 的债：正解是「提交任务 + 查询进度」两步式。</li>
 * </ul>
 *
 * <h3>★★ 失败处理：不吞异常，让它变成 500</h3>
 *
 * <p>和 {@code IndexRebuildJob}（吞掉记 ERROR）<b>刻意相反</b>。
 * 理由：那个任务抛出去的异常没有任何人看得见；
 * <b>而这里管理员正站在屏幕前等着结果</b> ——
 * 「点了重建、页面说成功、索引其实是空的」是这里最坏的失败，
 * 所以宁可给一个 500 + 原因，也不给一个 200。
 *
 * <h3>★ 权限：{@code hasRole("ADMIN")}</h3>
 *
 * <p>规则写在 {@code SearchSecurityConfig}（{@code /api/admin/**}）。
 * ★ 用<b>配置里的路径规则</b>而不是本方法上的 {@code @PreAuthorize}，
 * 是为了和另外三个服务的口径一致 ——
 * M4 之前本服务没有管理端点，这条规则是 S2 建服务时就先写好的。
 * <p>★ 这里仍然保留一个 {@code @PreAuthorize} 作<b>第二道</b>：
 * 万一将来有人在 {@code SearchSecurityConfig} 里调整了路径规则（比如改成 {@code /api/admin/search-*}），
 * 配置那道会静默失效，而这一道还在。
 * ★★ 两道锁的判据：<b>失效方式不同的两道锁才互为备份</b> ——
 * 抄两遍同一个条件不算。
 *
 * @author MickeyChen
 */
@Tag(name = "15. 管理端 · 搜索索引", description = "重建搜索索引。**仅 ADMIN**")
@RestController
@RequestMapping("/api/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    private final IndexRebuildService indexRebuildService;

    @Operation(
            summary = "全量重建搜索索引",
            description = """
                    把整个索引删掉重建，并把 content 里所有作品重新灌一遍。

                    ★ **这是运维端点，不是业务接口** —— 正常情况下不该被调用：
                      平时的索引更新是秒级的（作品变更 → MQ → 重建那一本）。
                      它的作用是「索引和数据库对不上了」时的兜底，以及验收/排障时的强制刷新。

                    ★ **重建过程中索引会短暂不存在**，这期间搜索自动走降级路径
                      （MySQL LIKE，仍然可用，只是排序退回阅读量）。

                    ★ 返回 `indexed` = 实际灌入的文档条数；`elapsedMs` = 耗时。

                    ★ **`skipped = true` 表示【本轮什么都没做】**（另一个重建正在进行，
                      分布式锁没抢到）—— 它**不是成功**，过一会儿再试。

                    ★ 失败时返回 HTTP 500（不是 200 + 业务码）：
                      这是基础设施故障，管理员需要立刻看到。

                    **仅 ADMIN。**""")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reindex")
    public Result<IndexRebuildService.RebuildReport> reindex() {
        return Result.success(indexRebuildService.rebuildAll());
    }
}
