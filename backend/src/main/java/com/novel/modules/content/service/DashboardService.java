package com.novel.modules.content.service;

import com.novel.modules.content.vo.DashboardStatsVO;
import com.novel.modules.content.vo.StatusDistVO;
import com.novel.modules.content.vo.TrendVO;

/**
 * 数据大盘。
 *
 * <p><b>★★ 这个接口在 {@code modules/content/} 下，不在 {@code modules/system/}，
 * 这是 P5 里最容易被做错的一个位置决定。</b>
 *
 * <p>直觉是「管理后台的东西当然放 system」，但那是错的，两个理由：
 * <ol>
 *   <li><b>会反转依赖箭头。</b>本项目规定 {@code content → system} 是唯一允许的方向，
 *       而且只有 {@link ContentUserLookup} 这一个接缝。大盘要同时读作品、章节、
 *       用户、评论 —— 其中<b>多数指标是内容指标</b>，它天然属于 content 层。
 *       放进 system 就等于让 {@code system → content}，第二期 M2
 *       「摘出 content」时会出现双向依赖，<b>拆不开</b>。</li>
 *   <li>{@code ContentUserLookup} 是给「content 读 system」用的，
 *       反方向既没有、也不该有对应的 Lookup —— 有了就是双向依赖的另一种写法。</li>
 * </ol>
 *
 * <p><b>★ 管理端不单独拆服务</b>（大纲 §175）：{@code admin-web} 调的就是
 * 各服务已有的管理接口。所以本项目里<b>没有也不该有 {@code modules/admin/}</b> ——
 * 「管理」是权限维度，不是业务领域，按领域分包才拆得开。
 *
 * @author MickeyChen
 */
public interface DashboardService {

    /**
     * 顶部卡片的八个数字。
     *
     * <p>★ 八个数字来自<b>同一条 SQL</b>，是同一瞬间的快照 ——
     * 理由见 {@code DashboardMapper#selectStats}。
     */
    DashboardStatsVO stats();

    /**
     * 最近 N 天的新增趋势（**零填充**）。
     *
     * @param days 天数。★ 会被夹到 1~90，见实现类 —— 不夹的话
     *             {@code ?days=100000} 会在内存里造十万个日期对象，
     *             一个请求就能把 CPU 打满
     */
    TrendVO trend(int days);

    /** 作品 / 章节的状态分布（**零填充**）。两个饼图用 */
    StatusDistVO statusDist();
}
