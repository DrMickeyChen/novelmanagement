package com.novel.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.entity.AiUsageLog;
import com.novel.modules.ai.mapper.AiUsageLogMapper;
import com.novel.modules.ai.service.AiUsageService;
import com.novel.modules.ai.vo.AiUsageSummaryVO;
import com.novel.modules.ai.vo.AiUsageVO;
import com.novel.modules.ai.integration.ContentUserLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * AI 用量账的读侧（E1）。
 *
 * <h3>★ 这个类只用两种东西：一次分页查询 + 一次批量取昵称</h3>
 *
 * <p>没有第三种。这是刻意的 —— 用量页是<b>唯一一张跨服务的只读表</b>
 * （昵称来自 auth），所以「一页几次远程调用」这件事在这里必须一眼可数：
 * <b>一次</b>（{@code nicknamesOf} 批量），不是每行一次。
 *
 * <h3>★★ {@code userName} 那一步是本类唯一会「降级」的地方，也是唯一安全的</h3>
 *
 * <p>{@code ContentUserLookup.nicknamesOf} 在 auth 挂掉时返回空 Map
 * （{@code AuthUserClientFallbackFactory} 的 {@code logThenDegrade}），
 * 于是本类的 Map 里查不到 → {@code userName} 为 null →
 * 全局 {@code non_null} 让它从 JSON 里消失 → 页面显示「用户 #7」。
 *
 * <p>★ 为什么这次降级是允许的：那句话说的是「这是 7 号用户，只是没拿到名字」，
 * 是一条<b>关于我们知道什么</b>的真话。而 {@code AuthUserClientFallbackFactory}
 * 的类注释里那张表，恰恰把隔壁的「（已删除）」判成了假话并因此拒绝降级 ——
 * <b>同一个空值喂给不同的兜底文案，结论是相反的</b>，所以这里引用了那条判定，
 * 而不是自己重新拍一次。
 *
 * <h3>★ 其余一切都不降级</h3>
 *
 * <p>token / 耗时 / 成功与否 / 失败原因全部来自本服务的库，
 * 查不到就是查不到（空列表），不存在「暂时拿不到」这一档 ——
 * 因为不需要跨任何进程。
 *
 * @author MickeyChen
 */
@Service
@RequiredArgsConstructor
public class AiUsageServiceImpl implements AiUsageService {

    /** 与 {@code AuditLogServiceImpl} 同一个默认值。★ 只是下限兜底，上限由 MP 的 maxLimit 管 */
    private static final long DEFAULT_PAGE_SIZE = 10;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AiUsageLogMapper aiUsageLogMapper;

    /**
     * ★ 这是本模块（{@code modules/ai}）<b>唯一</b>一处 import {@code modules/content} 的地方。
     *
     * <p>说清楚它为什么不算破口：
     * <ul>
     *   <li>它是<b>同进程内</b>的模块引用，<b>不是服务边界</b> ——
     *       铁律 1 管的是「哪个进程能调哪个进程」，这里两个类都在 novel-content 里。</li>
     *   <li>方向是<b>单向</b>的：{@code content} 不 import {@code ai}
     *       （grep 两个方向都验证过），所以不成环。</li>
     *   <li>★ 不自己新建一个 {@code @FeignClient} 去 auth 取昵称：
     *       那会让全项目的 Feign 客户端从 5 个变成 6 个，而
     *       {@code AuthUserClient} 已经把这条接缝（连它在 auth 挂掉时怎么降级）
     *       全部处理好了 —— <b>加第二个客户端等于把同一件事的判定做两遍，
     *       而两遍迟早会不一样。</b></li>
     * </ul>
     *
     * <p>★★ 这也是 S5 <b>没有</b>在用量列表里显示书名 / 章节名的原因：
     * 那需要往 {@code modules/content} 里读作品表，而「作者只能看自己作品」的权限判定
     * 还没设计（S6 的 {@code AiContextAssembler} 会做）。与其顺手开一个没有权限校验的口，
     * 不如先不放这一列 —— 归属仍然可以按「调用人」追下去。
     */
    private final ContentUserLookup userLookup;

    @Override
    public PageResult<AiUsageVO> pageUsage(long page, long size, Integer scene, Boolean success, Long userId,
                                           LocalDate startDate, LocalDate endDate) {

        /*
         * ★ 起止写反 → 明确报错，不返回空列表。
         *   空列表在这里和「这段时间没人用」长得一模一样，是最容易被放过去的假数据。
         *   （与 ContentUserLookup#userIdsByNickname 那条「宁可 500」同源。）
         */
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }

        /*
         * ★★★ 这两个局部变量【不能】内联进下面那两行 wrapper 里。
         *
         *   原先写的是 `.ge(startDate != null, AiUsageLog::getCreateTime, atStartOfDay(startDate))`，
         *   并在 atStartOfDay 上注释「只在非空时被调用（wrapper 的 condition 参数保证）」。
         *   ★★ 那句注释是错的，而 S5 第一次请求就打脸了：
         *       `condition` 只决定这个条件【用不用】，不决定这个参数【算不算】——
         *       Java 是【先求值实参，再调方法】，所以 `atStartOfDay(null)` 无条件执行。
         *   症状：`GET /api/admin/ai/usage` 不带任何筛选 → NPE → 500。
         *   ★★ 这恰好是本项目最坏的一种失败形状：**「不传筛选」是这一页最常用的那一档**
         *      （页面刚打开就是这样），所以它不是一个边角 case，是主路径。
         *
         *   ★ 教训与铁律 8 同源：**「condition 参数会保护它」是一个从未被验证的断言**，
         *     而它就写在那行代码旁边，读起来像已经验过了。
         *     凡是把「可能为 null 的转换」塞进 wrapper 实参的写法，都要先落到局部变量。
         */
        LocalDateTime from = startDate == null ? null : startDate.atStartOfDay();
        /*
         * ★★ lt（小于）而不是 le（小于等于）+ 次日 0 点。
         *   写成 le + endDate 的 00:00 的话，「筛今天」只会命中
         *   今天 00:00:00.000 那一毫秒 —— 结果几乎永远是空的，
         *   而且没有任何报错。
         */
        LocalDateTime to = endDate == null ? null : endDate.plusDays(1).atStartOfDay();

        Page<AiUsageLog> result = aiUsageLogMapper.selectPage(buildPage(page, size),
                new LambdaQueryWrapper<AiUsageLog>()
                        .eq(scene != null, AiUsageLog::getScene, scene)
                        /*
                         * ★ 布尔 → TINYINT：存储是 0/1，接口是 true/false。
                         *   这一行是那个转换的唯一落点 —— 别让它漏到 VO 之外。
                         */
                        .eq(success != null, AiUsageLog::getSuccess, Boolean.TRUE.equals(success) ? 1 : 0)
                        .eq(userId != null, AiUsageLog::getUserId, userId)
                        .ge(startDate != null, AiUsageLog::getCreateTime, from)
                        .lt(endDate != null, AiUsageLog::getCreateTime, to)
                        .orderByDesc(AiUsageLog::getId));

        return new PageResult<>(toVOList(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    @Override
    public AiUsageSummaryVO summary() {
        /*
         * ★ 「今天」只取一次。
         *   写成 atStartOfDay(LocalDate.now()) 和 atStartOfDay(LocalDate.now().plusDays(1))
         *   的话，两次 now() 中间跨过午夜就会得到一个【零长度】区间 ——
         *   于是「今日」显示 0，而那一刻的现实是刚过零点。
         *   概率极低，但代价是把一个真实的 0 和这个假的 0 混在一起，分不出来。
         */
        LocalDate today = LocalDate.now();

        AiUsageSummaryVO vo = new AiUsageSummaryVO();
        vo.setStatDay(today.format(DAY));
        vo.setToday(toStat(aiUsageLogMapper.statBetween(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay()), "今日"));
        vo.setTotal(toStat(aiUsageLogMapper.statAll(), "累计"));
        return vo;
    }

    /**
     * 实体列表 → VO 列表，<b>昵称一次批量取好</b>。
     *
     * <p>★ 这个方法的形状（先收集 id → 一次查 → 再装配）就是本项目所有列表接口的规矩。
     * 循环里调 {@code nicknameOf} 在 M1 之后是<b>一页 20 次跨进程 HTTP</b>，
     * 而代码上它只多了一个字母。
     */
    private List<AiUsageVO> toVOList(List<AiUsageLog> logs) {
        if (logs.isEmpty()) {
            /*
             * ★ 空页直接返回，不去问 auth 要一个空集合的昵称。
             *   ContentUserLookup 内部也会短路，但那省的是它的一次调用；
             *   这里省的是「有一次远程调用」这件事本身 —— 翻到最后一页之后的
             *   每一次点击都会走到这里。
             */
            return List.of();
        }
        Map<Long, String> names =
                userLookup.nicknamesOf(logs.stream().map(AiUsageLog::getUserId).toList());
        return logs.stream().map(l -> AiUsageVO.of(l, names.get(l.getUserId()))).toList();
    }

    /**
     * 聚合行 → VO。
     *
     * <p>★ {@code row == null} 时明确抛，不回落成全 0：
     * {@code COUNT(*)} 的查询<b>永远</b>返回恰好一行，拿不到只可能是出了别的事
     * （SQL 写错、连接串了库）。这时候给页面一张「今天 0 次调用」的卡片，
     * 就是把一次系统故障渲染成了一个业务事实 —— 正是本项目一路在防的东西。
     */
    private static AiUsageSummaryVO.Stat toStat(AiUsageLogMapper.StatRow row, String what) {
        if (row == null) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, what + "用量统计没有返回结果行");
        }
        AiUsageSummaryVO.Stat stat = new AiUsageSummaryVO.Stat();
        stat.setCalls(row.getCalls());
        stat.setSuccessCalls(row.getSuccessCalls());
        stat.setFailedCalls(row.getFailedCalls());
        stat.setTotalTokens(row.getTotalTokens());
        stat.setUntrackedTokenCalls(row.getUntrackedTokenCalls());
        stat.setDistinctUsers(row.getDistinctUsers());
        return stat;
    }

    /**
     * 分页兜底。★ <b>下限必须自己兜</b>：MP 对 {@code size < 1} 的处理是
     * <b>完全不拼 LIMIT，返回整张表</b>，既不是报错也不是退回默认值 ——
     * 一个 {@code ?size=-1} 就能把全部调用流水拉出来。
     *
     * <p>★ 上限不在这里兜（写不写都行，写了就是第二份真相）：
     * {@code MybatisPlusConfig} 的 {@code PaginationInnerInterceptor.setMaxLimit(100)} 是权威。
     * 把两条规矩写在同一处会让人以为它们效力相同。
     */
    private Page<AiUsageLog> buildPage(long page, long size) {
        return new Page<>(page < 1 ? 1 : page, size < 1 ? DEFAULT_PAGE_SIZE : size);
    }

    /*
     * ★ 曾经这里有一个 `private static LocalDateTime atStartOfDay(LocalDate date)`，
     *   注释写着「只在非空时被调用（wrapper 的 condition 参数保证）」。那句话是错的，
     *   而且它把错误藏了整整一轮 —— 见 pageUsage 里那段注释。
     *   删掉它，而不是给它补一个判空：**那个形状本身就是陷阱**，
     *   留着的话下一个人还会再写一次 `.ge(cond, col, helper(maybeNull))`。
     */
}
