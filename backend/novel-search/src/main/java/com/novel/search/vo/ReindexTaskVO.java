package com.novel.search.vo;

import com.novel.search.enums.ReindexTaskStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * <b>一次异步重建任务的当前状态（M5 · S9）。</b>{@code GET /api/admin/search/reindex/{taskId}} 的返回体。
 *
 * <h3>★★★ 所有「还不知道」的字段一律用包装类型，不用基础类型</h3>
 *
 * <p>任务刚提交时，{@code indexed} / {@code elapsedMs} / {@code skipped} / {@code finishedAt}
 * <b>都还不知道</b>。如果它们是 {@code long} / {@code boolean}，
 * 序列化出去就是 {@code 0} / {@code false} / {@code 0} ——
 * 而 {@code indexed=0} 在业务上是一个<b>有意义的、而且是坏消息</b>的值
 * （「一本都没灌进去」）。
 *
 * <p>★★ 于是同一个 {@code 0} 会同时表示两件相反的事：
 * 「<b>还没开始数</b>」和「<b>数完了，是零</b>」。前端没法区分，
 * 只能二选一地猜 —— 而两种猜法都会在另一半情况下给出错误提示。
 *
 * <p>★ 用 {@code Long} / {@code Boolean}，且项目全局配了
 * {@code default-property-inclusion: non_null} →
 * <b>未知的字段【从 JSON 里消失】，而不是变成 0</b>。
 * 前端看到字段不存在 = 还不知道；看到 {@code "indexed": 0} = 确实一条都没灌。
 *
 * <p>★★ 这条判据不是 S9 发明的，它就是 {@code ShelfStatusVO.onShelf} 那条
 * 「一律用 {@code Boolean} 而不是 {@code boolean}——布尔字段一不小心漏填，
 * 表现正好是『看起来很正常』」在本类上的应用。
 * <b>M5 的判断 1 把「降级只对可见缺失成立」写成约定，本类是它在【异步任务】这个新场景里的落地。</b>
 *
 * <h3>★★ {@code skipped} 和 {@code status} 是【两个】字段，不许合并</h3>
 *
 * <p>M4 就把 {@code skipped} 加进 {@code RebuildReport} 了，理由那条实测教训原样适用：
 * <b>「没做」（没抢到锁）和「做了，结果是 0 条」在响应里必须长得不一样。</b>
 *
 * <pre>
 *   status=SUCCEEDED, skipped=true , indexed=0   → 本轮【什么都没做】，另一个重建在跑
 *   status=SUCCEEDED, skipped=false, indexed=0   → ★ 真做了，却一条都没灌进来 —— 这是【事故】
 * </pre>
 *
 * <p>★ 所以本类<b>刻意不给一个 {@code success} 布尔字段</b>去「简化」它：
 * 那会把上面两行重新压成同一个表现，正好抵消 {@link ReindexTaskStatus} 存在的理由。
 *
 * @param taskId     任务 id（提交时返回的那个）
 * @param status     见 {@link ReindexTaskStatus}。★ 它回答的是「能不能停下来了」
 * @param submitterId 是谁触发的。<b>运维痕迹</b>——页面上不止一个人有 ADMIN 权限时，
 *                   「谁在下午三点重建过」是排查的第一个问题
 * @param startedAt  提交时刻（epoch 毫秒）
 * @param finishedAt 结束时刻；★ <b>RUNNING 时为 {@code null}</b>（从 JSON 里消失）
 * @param indexed    ★ 实际灌入的文档条数（= 作品总数）；未知时为 {@code null}
 * @param elapsedMs  重建耗时；未知时为 {@code null}
 * @param skipped    ★ 见类注释：{@code true} = 没抢到锁、本轮<b>什么都没做</b>
 * @param error      失败原因；只有 {@code status=FAILED} 时才有值。
 *                   ★ <b>这是和「永远 RUNNING」相对的那一半</b>：
 *                   没有它，失败的任务在轮询方眼里就是「还没好」
 * @author MickeyChen
 */
public record ReindexTaskVO(
        String taskId,
        ReindexTaskStatus status,
        Long submitterId,
        Long startedAt,
        Long finishedAt,
        Long indexed,
        Long elapsedMs,
        Boolean skipped,
        String error
) {

    /**
     * 把 epoch 毫秒拼成给人看的字符串 —— <b>只用于日志，不进 JSON。</b>
     *
     * <p>★ 不放一个 {@code LocalDateTime} 字段出去：那会引入时区/格式的序列化约定，
     * 而这个字段的消费者是日志和排障的人。
     * 前端要展示的话，把 {@code startedAt} 这个毫秒数交给 {@code utils/format} 即可 ——
     * 三端已经有一份统一的格式化工具了。
     *
     * <p>★★ 刻意做成 {@code static} 工具方法而不是 {@code toString()} 覆盖：
     * record 的 {@code toString()} 被 {@code log.info("{}", task)} 隐式调用，
     * 覆写它会连带改变所有日志行的长相，而那种「顺手改了一个地方、
     * 所有日志格式都变了」的改动是排查时最不想遇到的。
     */
    public static String humanTime(Long epochMillis) {
        if (epochMillis == null) {
            return "-";
        }
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
