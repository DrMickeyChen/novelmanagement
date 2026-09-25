package com.novel.modules.ai.service;

import com.novel.common.result.PageResult;
import com.novel.modules.ai.vo.AiUsageSummaryVO;
import com.novel.modules.ai.vo.AiUsageVO;

import java.time.LocalDate;

/**
 * AI 用量账的<b>读</b>侧（E1）。
 *
 * <h3>★★ 为什么是「读侧」，而不是把写也塞进来</h3>
 *
 * <p>{@code ai_usage_log} 的写入只有一个入口：{@code AiUsageRecorder}（S4 建的）——
 * 它跑在生成线程上、在 {@code finally} 里写、<b>成功失败都写</b>。
 * 本接口只负责把它读出来。
 *
 * <p>★ 合成一个类也能跑，但那样「谁能往这张表里放东西」就有两个答案了，
 * 而这张表是<b>记账表</b>：它的每一行都对应一次真实支出。
 * <b>记账表的写入路径越少越好，最好只有一个</b> —— 这是把它拆开唯一的、也是足够的理由。
 *
 * <h3>★★ 这个页面存在的理由是「下一步该做什么」</h3>
 *
 * <p>{@code AiFailure} 的类注释把这件事说透了：失败原因分得细，
 * 是因为它们指向的下一步动作完全不同（{@code upstream_401} → 管理员换 Key，
 * {@code upstream_404} → 检查 {@code base_url}，{@code upstream_429} → 等一会儿）。
 * <b>本接口的职责就是保证那些信息一个都不丢地到达页面</b> ——
 * 尤其是 {@link AiUsageVO#getErrorDesc()} 和 {@link AiUsageSummaryVO.Stat#getUntrackedTokenCalls()}。
 *
 * @author MickeyChen
 */
public interface AiUsageService {

    /**
     * 分页查调用流水，<b>按 id 倒序</b>（即从新到旧）。
     *
     * <p>★ 排序用 {@code id} 不用 {@code create_time}：同秒的 {@code DATETIME}
     * 会打平，翻页顺序会跳。这条是本项目的既有规矩（见 {@code AuditLogServiceImpl} 同一处）。
     *
     * <h4>筛选参数（全部可空 = 不筛）</h4>
     * <table border="1">
     *   <caption>筛选口径</caption>
     *   <tr><th>参数</th><th>含义</th><th>判据</th></tr>
     *   <tr><td>{@code scene}</td><td>场景码 1~5</td>
     *       <td>★ 收 {@code Integer} 不收 {@code AiScene}：认不出来的码要能<b>查出来是空的</b>，
     *           而不是被 Spring 的枚举绑定拦成 400 —— 前者说明「这个场景还没人用过」，
     *           后者让调用方以为参数写错了</td></tr>
     *   <tr><td>{@code success}</td><td>{@code true} 只看成功 / {@code false} 只看失败</td>
     *       <td>★ 用 {@code Boolean}（可空）而不是 {@code boolean}：
     *           {@code boolean} 的默认值是 {@code false}，于是「不筛」会静默变成
     *           <b>「只看失败」</b> —— 一个不传参数就生效的筛选条件</td></tr>
     *   <tr><td>{@code userId}</td><td>调用人</td>
     *       <td>是配额的主体，所以「这个人今天花了多少」是最自然的问法</td></tr>
     *   <tr><td>{@code startDate} / {@code endDate}</td><td>{@code yyyy-MM-dd}，<b>两端都含</b></td>
     *       <td>★ 见下面那段</td></tr>
     * </table>
     *
     * <h4>★★ 日期是「含当天」的，而库里存的是 DATETIME</h4>
     *
     * <p>所以 {@code endDate} 必须换算成<b>次日 0 点</b>再取左闭右开
     * （{@code create_time >= startDate 00:00 AND create_time < endDate+1 00:00}）。
     * <b>少了那个 +1 天，用户筛「今天」会一行都查不到</b> —— 而那种空结果
     * 长得和「今天确实没人用」一模一样，是最容易被放过去的一种错。
     *
     * <p>★ 起止写反了（{@code start > end}）会<b>明确报错</b>，不返回空列表：
     * 同样是「看起来正常的空数据」，理由同上。
     *
     * @throws com.novel.common.exception.BusinessException 起止日期写反（{@code PARAM_ERROR}）
     */
    PageResult<AiUsageVO> pageUsage(long page, long size, Integer scene, Boolean success, Long userId,
                                    LocalDate startDate, LocalDate endDate);

    /**
     * 顶部汇总卡片：今日 + 累计。
     *
     * <p>★ 「今日」的边界是<b>服务端时区的 {@code LocalDate.now()}</b> ——
     * 与配额计数器（{@code AiQuotaServiceImpl} 的 {@code novel:ai:quota:{userId}:{yyyyMMdd}}）
     * <b>必须是同一个口径</b>。否则会出现「用量页说今天用了 12 次、
     * 配额说已用 14 次」这种对不上的账，而两边都没有报错。
     *
     * <p>★★ 日期从哪来这件事，{@code AiQuotaServiceImpl} 已经论证过一轮：
     * 「每天重置」靠的是<b>键名换了一天</b>，不是定时任务。这里跟着它走，
     * 也就不用回答「零点那一刻谁去归档」这种问题。
     */
    AiUsageSummaryVO summary();
}
