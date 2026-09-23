package com.novel.search.controller;

import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import com.novel.common.result.ResultHttpStatus;
import com.novel.search.service.ReindexTaskService;
import com.novel.search.vo.ReindexSubmitVO;
import com.novel.search.vo.ReindexTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>管理端 —— 搜索索引的运维端点（M4 / S8 / S9）。</b>只有 ADMIN 能碰。
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
 * <h3>★★★ S9：它是【两步式】的，以及这一步买到和卖掉了什么</h3>
 *
 * <pre>
 *   S8 之前（同步）：  POST /reindex  ─────────────► RebuildReport{skipped,indexed,elapsedMs}
 *                      一个请求里既提交又拿结果
 *
 *   S9 之后（异步）：  POST /reindex  ──► {taskId}            ← 收据
 *                      GET  /reindex/{taskId} ──► ReindexTaskVO ← 结果（轮询）
 * </pre>
 *
 * <table border="1">
 *   <caption>这次改动的账</caption>
 *   <tr><th></th><th>内容</th></tr>
 *   <tr>
 *     <td>★ <b>买到</b></td>
 *     <td>网关/浏览器<b>不再被一个长任务占住</b>。
 *         同步时代的失败模式是「库大到超过读超时（30~60 秒）→ 管理员看到超时，
 *         而重建其实还在跑」—— 他收到的是一个<b>无法判断</b>的结果。
 *         现在「还没好」是一个合法答案（{@code status=RUNNING}）。</td>
 *   </tr>
 *   <tr>
 *     <td>★★ <b>卖掉</b></td>
 *     <td>① 管理员<b>要轮询</b>，不再是「点一下就好」；
 *         ② <b>「提交成功」不再等于「重建成功」</b> ——
 *         {@code POST} 的 200 只说明任务进了队列。
 *         ★ 这一条必须写死在文档里，否则下一个人会把 200 读成「索引已经好了」，
 *         而那正是异步化引入的<b>新</b>的误读方式。</td>
 *   </tr>
 *   <tr>
 *     <td>★ <b>不保留同步入口</b></td>
 *     <td>两个入口（一个同步一个异步）会有一条<b>没人维护</b>：
 *         改重建逻辑时很容易只测常用的那个，另一个悄悄烂掉。
 *         「不方便」好过「有一个假的」。</td>
 *   </tr>
 * </table>
 *
 * <h3>★★ 失败处理：从「变成 500」改成「变成状态字段」</h3>
 *
 * <p>M4 时代这里的选择是<b>不吞异常、让它变成 500</b>，理由是
 * 「管理员正站在屏幕前等着结果」。<b>异步化之后那个理由消失了</b> ——
 * 提交请求早就返回了，{@code rebuildAll()} 是在池线程上抛的，
 * <b>没有任何人接得住它</b>。所以异常改由 {@code ReindexTaskStore.markFailed}
 * 落成 {@code status=FAILED} + {@code error}，管理员在轮询结果里看到。
 *
 * <p>★ 两种做法服务的是同一个目标（<b>失败必须看得见</b>），只是载体换了：
 * 同步时代载体的 HTTP 状态码，异步时代载体是任务状态。
 * ★★ <b>仍然保留 500 的那一处</b>：提交时队列满 ——
 * 那一次失败发生在<b>请求线程上</b>，还来得及告诉调用方。
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

    private final ReindexTaskService reindexTaskService;

    @Operation(
            summary = "提交一次全量重建（异步）",
            description = """
                    把 content 里所有作品重新灌进一个新的物理索引，然后原子切换别名。

                    ★ **这是运维端点，不是业务接口** —— 正常情况下不该被调用：
                      平时的索引更新是秒级的（作品变更 → MQ → 重建那一本）。
                      它的作用是「索引和数据库对不上了」时的兜底，以及验收/排障时的强制刷新。

                    ★ **S9 起它是异步的**：返回的 `taskId` 是一张**收据，不是结论**。
                      `code=200` 只表示任务已进队列，**不代表重建已经成功**。
                      要拿结果请轮询 `GET /api/admin/search/reindex/{taskId}`，
                      直到 `status` 不是 `RUNNING`。

                    ★ 重建过程中检索**不会中断**：新索引灌满之前别名仍指向旧索引
                      （S8 的别名原子切换），所以不存在「搜到一半没有结果」的窗口。

                    ★ 失败方式有两种，看的地方不同：
                      - 提交时队列满 → **HTTP 500**（本次没派出去，请稍后重试）
                      - 提交成功但重建失败 → 轮询结果里 `status=FAILED` + `error`

                    **仅 ADMIN。**""")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reindex")
    public Result<ReindexSubmitVO> reindex() {
        return Result.success(reindexTaskService.submit());
    }

    @Operation(
            summary = "查询重建任务的进度 / 结果",
            description = """
                    轮询这个接口直到 `status` 不再是 `RUNNING`。

                    ★ `status` 三态：
                      - `RUNNING`   —— 还在跑。★ 连 `startedAt` 一起看：
                        开始很久还没结束，多半是上一次进程被杀了留下的记录（记录会在 24 小时后过期）
                      - `SUCCEEDED` —— 跑完了。**这时才该看 `indexed` / `skipped`**
                      - `FAILED`    —— 失败了，`error` 里有原因

                    ★★ `skipped` 和 `indexed` 必须**一起看**，它俩的组合是有含义的：
                      - `skipped=true`            → 本轮**什么都没做**（没抢到锁：另一个重建正在跑，
                        或上一次重建被强杀、锁还没到期）
                      - `skipped=false, indexed=0` → 真做了，却一条都没灌进来 —— **这是事故**
                        （★ 注意：重建**失败**不会走到这里，它是 `status=FAILED`）

                    ★ 任务记录保留 24 小时，之后查询返回 **404**。

                    **仅 ADMIN。**""")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/reindex/{taskId}")
    public ResponseEntity<Result<ReindexTaskVO>> reindexStatus(@PathVariable String taskId) {
        ReindexTaskVO task = reindexTaskService.status(taskId);
        if (task == null) {
            /*
             * ★★ 这里用 404（而不是 200 + 业务码），依据是 ResultCode 的类注释：
             *   「未登录/无权限/资源不存在」这类必须用 401/403/404，
             *   一旦放成 1001+ 就失去了监控可见性，而且和已有的 401 语义打架。
             *   ★「任务不存在」确实就是「资源不存在」—— 它是一个
             *   【查错了目标】的请求（id 打错，或记录已过 TTL），
             *   不是「请求合法、只是业务上说不通」（那种才返 200 + code）。
             *
             * ★★★ 而它必须用 ResponseEntity 才拿得到真正的 404 ——
             *   这一条是实测出来的容易踩的坑：【直接 return Result.error(...)，
             *   HTTP 状态码仍然是 200】。因为 ResultHttpStatus 只在
             *   GlobalExceptionHandler / SecurityResponseWriter / 网关那几个地方被调用，
             *   【普通 @RestController 的返回值没有人去翻译】。
             *   ★ 同一个理由和同一个写法见 PingController#fail ——
             *   那里的注释说得很直白：「返回值 200 就等于没有监控」。
             *   本端点和 pingDb/pingRedis 是同一类东西（给运维看的），所以照抄它。
             *
             * ★ 更实际的一层：轮询代码天然只看 HTTP 状态。返 200 的话，
             *   调用方必须【额外判断】body 里的 code 才能区分
             *   「拿到了状态」和「任务没了」—— 而少写那个判断的后果是
             *   把 null 当成任务对象用，报一个和真实原因毫无关系的错。
             */
            Integer code = ResultCode.NOT_FOUND.getCode();
            return ResponseEntity.status(ResultHttpStatus.of(code))
                    .body(Result.error(code, "重建任务不存在或已过期（任务记录保留 24 小时）"));
        }
        return ResponseEntity.ok(Result.success(task));
    }
}
