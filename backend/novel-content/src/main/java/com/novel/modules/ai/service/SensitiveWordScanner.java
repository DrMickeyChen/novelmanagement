package com.novel.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.entity.SensitiveWord;
import com.novel.modules.ai.mapper.SensitiveWordMapper;
import com.novel.modules.ai.vo.SensitiveWordHitVO;
import com.novel.modules.ai.vo.SensitiveWordScanVO;
import com.novel.modules.ai.vo.SensitiveWordStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 违禁审核的<b>第一层：关键词词库</b>（E1/S7）—— 进程内不可变快照 + Aho-Corasick 自动机。
 *
 * <h3>★★★ 判据：这个词库是「参与筛选的输入」，所以它一律【不降级】（铁律 10）</h3>
 *
 * <p>「当成空词库继续跑」= 违禁内容进库，<b>而且日志上没有任何异常</b> ——
 * 这是铁律 8「一条不会失败的护栏」的教科书形态，<b>而且是最坏的一种：它连「我没在工作」都不说。</b>
 * 所以下面三种情况全部<b>拒审</b>（{@link #requireReady} 抛 {@code AI_WORD_LIB_UNAVAILABLE(5007)}）：
 *
 * <table border="1">
 *   <caption>三种状态</caption>
 *   <tr><th>状态</th><th>怎么产生</th><th>日志级</th><th>管理员该做什么</th></tr>
 *   <tr><td>快照<b>不存在</b>（{@code loaded=false}）</td><td>从没加载成功过</td><td><b>ERROR</b></td><td>查 DB / 连接</td></tr>
 *   <tr><td>快照存在但<b>一条词都没有</b></td><td>库里的词被删光 / 全停用</td><td><b>WARN</b></td><td>去录词</td></tr>
 *   <tr><td>两者都正常</td><td>—</td><td>—</td><td>放行</td></tr>
 * </table>
 * ★ 前两档<b>故意分开</b>：报成同一句话的话，管理员会拿着「词库不可用」去查数据库连接，
 * 而真正该做的是加一个词。★ 两档的<b>文案也不同</b> —— 见 {@link #requireReady()}。
 *
 * <h3>★★ 为什么必须缓存成快照，而模板却刻意不缓存（判断 2.4 的对照）</h3>
 *
 * <p>判据是「构建代价落不落在<b>请求路径</b>上」：
 * <ul>
 *   <li><b>模板</b>：按唯一键读一行，构建代价 ≈ 0 → 不缓存，「改了立刻生效」免费拿到；</li>
 *   <li><b>词库</b>：要把<b>整张表</b>装进一个自动机，而这件事发生在<b>每条待审内容的消费路径</b>上
 *       —— 一篇 5000 字的章节，每次审核都重建一次全表自动机，那是拿运营的改动频率
 *       去惩罚每一次审核。所以必须缓存。</li>
 * </ul>
 * ★★ 代价是「改了不立刻生效」—— 这个代价由三件事一起兜住，而它们都是诚实的：
 * ① 管理端有一个<b>显式的 reload 端点</b>（管理员改完自己按一下，这是正常的运维动作）；
 * ② 一个<b>定时兜底</b>（{@code SensitiveWordReloadJob}，每 10 分钟）；
 * ③ 管理页把「库里的条数」和「快照里的条数」并排显示 —— <b>陈旧变成一个看得见的数字</b>。
 *
 * <h3>★★★ 发布规则：加载失败<b>不销毁</b>一份有效的旧快照（这条比方案原文更精确）</h3>
 *
 * <p>方案原文说的是「加载失败 → 拒审」。那只覆盖了「压根没有快照」这一种；
 * 而<b>已经有一份好快照、只是这一次 reload 失败了</b>（DB 闪断一秒）是另一种，
 * 两者该走不同的路：
 * <pre>
 *   没有快照              → 拒审（fail-closed）        ← 方案原文说的就是这一档
 *   有好快照但 reload 失败 → 【继续用旧的】，记 ERROR    ← ★ 本类补上的这一档
 * </pre>
 * ★★ 理由：<b>一份稍微旧的词库和一份最新的词库，在「筛不筛得住」上没有性质差别</b> ——
 * 词库本来就<b>永远是滞后的</b>（今天新出的违禁词，今天之前谁都没录进去）。
 * 把旧快照丢掉，换来的是<b>审核全线停摆</b>，而安全性一点没提高。
 * ★ 这与「接受多实例之间的陈旧」（{@link SensitiveWordStatusVO} 的类注释）是同一个取舍：
 * <b>接受陈旧，不接受「假装审过了」</b> —— 而后者正是「用空词库继续跑」干的事。
 *
 * <h3>★★ 热更新的形状：不可变快照 + {@code AtomicReference}</h3>
 *
 * <p>自动机构建完成后<b>再也不被修改</b>（所有 {@code HashMap} 都是构建期的局部产物），
 * 靠 {@code AtomicReference.set} 的安全发布交给读线程 —— 所以<b>扫描路径不加锁</b>。
 * ★★ 这一点很重要：{@link #reload()} 要查库，DB 卡住时会拖很久，
 * 而审核请求<b>绝不能等在它后面</b>。读路径只做一次 {@code get()} 加一次引用读。
 *
 * <h3>★★ 已知的能力边界（诚实记账，不假装它是全的）</h3>
 *
 * <p>这一层是<b>精确子串匹配</b>，不做任何变形归一化 —— 所以下面这些绕过它<b>是预期的</b>：
 * 中间插空格/标点、同音字、繁简混写、拼音、变体字。
 * ★★ 这不是「没做完」，而是这一层的定位：大纲自己写着它「快、免费、<b>误杀多</b>」，
 * 它是给第二层（AI 语义复核）和第三层（人工）<b>减流量</b>的粗筛，不是终审。
 * 反过来，把它做成「去掉所有非汉字再匹配」看着更严，实际会把
 * 「上海」这类短词和正常文本的边界吃掉 —— <b>误杀率上升的方向在这里是危险的</b>，
 * 因为命中会进人工队列，而人工队列被噪声灌满就等于没有队列。
 * ★ 真正的补强方向是「按语义判」= 第二层，不是把第一层写成半个正则引擎。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordScanner implements ApplicationRunner {

    private final SensitiveWordMapper sensitiveWordMapper;

    /**
     * 当前快照。<b>可能是 {@code null} —— 那是「没加载成功过」，是一个有意义的状态。</b>
     *
     * <p>★ 不要用「空自动机」表示它：那样「加载失败」和「词库是空的」就合并了，
     * 而这两件事的日志级别、文案、修法都不一样（见类注释那张表）。
     */
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    /** 最近一次 reload 的<b>尝试</b>时间，成功失败都记 */
    private volatile LocalDateTime lastReloadAt;

    /** 最近一次失败的原因（只有类名 + 一行摘要）。成功后由 {@link #reload()} 清空 */
    private volatile String lastError;

    private volatile LocalDateTime lastErrorAt;

    /* ==================== 启动即加载 ==================== */

    /**
     * 启动时装一次快照。
     *
     * <p>★★ <b>为什么是 {@code ApplicationRunner} 而不是 {@code @PostConstruct}</b>：
     * 同 {@code IndexInitializer} 的论证 —— {@code @PostConstruct} 的约定是
     * 「这个 bean 自己准备就绪」，而这里做的是「让一个<b>外部系统</b>（词库表）的状态
     * 在本进程里有一个已知的投影」。★ 而且 Runner 跑在<b>容器就绪之后</b>，
     * 那时连接池与 MyBatis 都已可用。
     *
     * <p>★★ <b>失败【不会】让服务起不来</b>，而它安全的理由是
     * {@link #reload()} <b>自己把异常全部吞掉</b>（同 {@code IndexService#ensureIndex} 的形状）——
     * ★ 不是「选了 Runner 就安全」：Runner 抛异常一样会让启动失败。
     * <b>谁把 reload 改成向上抛，就等于把「词库表读不到」升级成「content 服务起不来」。</b>
     * <p>★ 而「起得来但没快照」在这里是<b>安全的</b>：所有审核会被 {@link #requireReady} 拒掉，
     * 而不是当成空词库放过去。
     */
    @Override
    public void run(ApplicationArguments args) {
        SensitiveWordStatusVO status = reload();
        if (Boolean.TRUE.equals(status.getReady())) {
            log.info("敏感词库快照已就绪 | 词条数={} | 版本={}", status.getWordCount(), status.getVersion());
        } else {
            /*
             * ★ WARN 而不是 ERROR：这一行最常见的成因是「新库还没录词」，
             *   那是配置状态、不是故障。真正的加载失败在 reload() 里已经记过 ERROR。
             */
            log.warn("敏感词库快照【未就绪】，此刻所有审核都会被拒（fail-closed）"
                            + " | loaded={} | wordCount={} | 最后错误={}",
                    status.getLoaded(), status.getWordCount(), status.getLastError());
        }
    }

    /* ==================== 读路径（不加锁） ==================== */

    /**
     * 扫一段文本 —— <b>第一层的全部对外能力就这一个方法</b>。
     *
     * <p>★ 先 {@link #requireReady()}（不可用就拒审），再扫。顺序不能反：
     * 先扫再判「词库空不空」的话，一次空库扫描会返回一个<b>好看的 {@code maxLevel=0}</b>，
     * 而调用方只要漏判一次，它就成了一句「审核通过」。
     * <b>让「不可用」在类型上就产不出结果</b>，比让每个调用方都记得判一次可靠。
     * ★ S8 的 MQ 消费者用的是同一个方法 —— 它不关心调用方是 HTTP 还是 MQ。
     *
     * @param text 待扫文本。{@code null} 当作空串（没有内容可扫，不是错误）
     * @return 命中的词 + 本次判定所依据的快照信息
     * @throws BusinessException {@code AI_WORD_LIB_UNAVAILABLE(5007)} —— 词库不可用
     */
    public SensitiveWordScanVO scan(String text) {
        Snapshot snapshot = requireReady();
        Automaton automaton = snapshot.automaton();

        /*
         * ★★ LinkedHashMap 去重 + 保持「首次出现的顺序」：
         *   同一个词在正文里出现 20 次只算一条 —— 审核要的是「命中过哪些词」，不是词频。
         *   用 LinkedHashMap 而不是 HashSet，是为了让界面和日志里的顺序是稳定的
         *   （同一段文本扫两次结果列表逐字相同，验收才可比对）。
         */
        Map<String, SensitiveWordHitVO> hits = new LinkedHashMap<>();
        int maxLevel = 0;

        if (text != null && !text.isEmpty()) {
            Automaton.Node cursor = automaton.root();
            for (int i = 0; i < text.length(); i++) {
                cursor = automaton.step(cursor, text.charAt(i));
                /*
                 * ★★ 沿失败指针收集【当前状态链上的所有】终结节点 ——
                 *   这一步就是 Aho-Corasick 相对「对每个词各跑一次 indexOf」的全部收益：
                 *   「占位敏感词甲乙」同时命中「占位敏感词甲」与「占位敏感词乙」，
                 *   而它们在同一条失败链上，一次遍历全都拿到。
                 * ★ 循环条件是 p != root：根节点不可能是任何词的结尾
                 *   （词非空，空词在 build() 里就被跳过了）。
                 */
                for (Automaton.Node p = cursor; p != automaton.root(); p = p.fail) {
                    if (p.word == null) {
                        continue;
                    }
                    hits.putIfAbsent(p.word, new SensitiveWordHitVO(p.word, p.level, p.category));
                    maxLevel = Math.max(maxLevel, p.level);
                }
            }
        }

        SensitiveWordScanVO vo = new SensitiveWordScanVO();
        vo.setMaxLevel(maxLevel);
        vo.setHitCount(hits.size());
        vo.setHits(new ArrayList<>(hits.values()));
        /*
         * ★★ 这两个字段是「这次判定凭什么做出」的物证，必须有 ——
         *   没有它们的话，一次「无命中」既可能是「词库好好的、文本是干净的」，
         *   也可能是「快照是旧的、新词还没进来」，而调用方无从分辨。
         *   详见 SensitiveWordScanVO 的类注释。
         */
        vo.setWordCount(snapshot.wordCount);
        vo.setVersion(snapshot.version);
        return vo;
    }

    /**
     * 拿到当前快照，或者<b>拒审</b>。
     *
     * <p>★★★ 这是本类唯一一处「拒绝服务」，也是铁律 10 的直接兑现 ——
     * 词库是参与筛选的输入，<b>它取不到的时候，结果集不能是「什么也没筛出来」</b>。
     *
     * <p>★ 两档的<b>日志级别与文案都不同</b>：合成一句的话，管理员会拿着「词库不可用」
     * 去查数据库连接，而真相是「还没录词」。
     */
    private Snapshot requireReady() {
        Snapshot snapshot = snapshotRef.get();

        if (snapshot == null) {
            /*
             * ★ ERROR：这是【故障】。快照压根没建起来 —— 从没加载成功过。
             *   排查方向是「词库表读不到」（连接 / 权限 / 表不存在），不是「去录词」。
             */
            log.error("敏感词库快照【不存在】，本次审核被拒（fail-closed）"
                            + " | 最后错误={} | 最后失败时间={}", lastError, lastErrorAt);
            throw new BusinessException(ResultCode.AI_WORD_LIB_UNAVAILABLE,
                    "敏感词库尚未加载成功，审核已中止。请检查数据库连接后点「立即重载」。");
        }

        if (snapshot.wordCount == 0) {
            /*
             * ★ WARN：这是【配置状态】，不是故障。库里的词被删光或全部停用了。
             *   ★★ 它必须拒审，不能放行 —— 「词库是空的」和「这段文字很干净」
             *      在结果里长得一模一样，放行就等于把前者渲染成了后者。
             */
            log.warn("敏感词库中【没有任何启用中的词】，本次审核被拒（fail-closed）"
                    + " | 请在词库管理里添加词条，或检查是不是把词全停用了");
            throw new BusinessException(ResultCode.AI_WORD_LIB_UNAVAILABLE,
                    "敏感词库中没有任何启用的词，审核已中止。请先在词库管理里添加词条。");
        }

        return snapshot;
    }

    /* ==================== 写路径（reload） ==================== */

    /**
     * 重建快照 —— <b>启动 / 定时兜底 / 管理端按钮三条触发源共用的唯一实现</b>。
     *
     * <p>★★ <b>本方法【永不抛异常】。</b> 这不是「偷懒不处理」，而是它的三个调用方
     * 都受不了异常：{@code ApplicationRunner} 抛了 → 服务起不来；
     * {@code @Scheduled} 抛了 → 没人接得住，只剩框架打印的一行栈；
     * 管理端抛了 → 管理员看到 500，且不知道快照现在是什么状态。
     * ★ <b>失败被完整地表达在返回值里</b>（{@link SensitiveWordStatusVO#getLastError()}），
     * 且不可用状态会被 {@link #requireReady()} 在每一次审核时重新拦一遍 ——
     * 「加载失败」这件事有两个独立的出口，不会因为这里吞了异常而消失。
     *
     * <p>★★ <b>{@code synchronized}：并发 reload 没有意义，且会互相覆盖结果。</b>
     * 三档场景真的会撞上：管理端点「立即重载」的同时定时兜底正好触发。
     * ★ 加锁的<b>范围很小</b>：只保护「读库 + 构建 + 发布」这一串，
     * 而<b>读路径不加这把锁</b>（它只做一次 {@code AtomicReference.get()}）——
     * 所以一次卡住的 reload 不会让审核请求排队。
     *
     * @return reload <b>之后</b>的状态（含库里的行数，好让管理页一次调用就能并排显示）
     */
    public synchronized SensitiveWordStatusVO reload() {
        lastReloadAt = LocalDateTime.now();
        long start = System.currentTimeMillis();

        try {
            /*
             * ★ 只取 enabled=1 的词 —— 这正是 idx_enabled 存在的理由。
             *   停用的词压根不进自动机，所以「停用后立刻不再命中」这件事
             *   不需要在扫描时判一次（那是每条待审内容都要付的代价）。
             */
            List<SensitiveWord> rows = sensitiveWordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                    .eq(SensitiveWord::getEnabled, 1)
                    .orderByAsc(SensitiveWord::getId));

            Automaton automaton = Automaton.build(rows);

            Snapshot previous = snapshotRef.get();
            int version = previous == null ? 1 : previous.version + 1;

            /*
             * ★★ 注意：词条数为 0 时【照样发布】这个空快照，version 照样 +1。
             *   理由：「词被删光了」和「加载失败」是两件必须能分开的事，
             *   而「发布了一个 0 条的快照」恰好就是前者的形状 ——
             *   requireReady() 认得出它（走 WARN 那档），
             *   而「快照是 null」永远只表示后者。
             * ★ 若这里改成「0 条就不发布」，两者立刻混成一档。
             */
            snapshotRef.set(new Snapshot(automaton, automaton.wordCount, version, LocalDateTime.now()));

            lastError = null;
            lastErrorAt = null;

            log.info("敏感词库快照已重建 | 词条数={} | 版本={} | 耗时={}ms",
                    automaton.wordCount, version, System.currentTimeMillis() - start);

        } catch (Exception e) {
            /*
             * ★★★ 这里【不动】snapshotRef —— 见类注释「加载失败不销毁一份有效的旧快照」。
             *   一句话：旧快照换来的是「稍微滞后」，丢掉它换来的是「审核全线停摆」，
             *   而后者并不更安全。
             */
            lastError = e.getClass().getSimpleName() + ": " + brief(e.getMessage());
            lastErrorAt = LocalDateTime.now();
            /*
             * ★ ERROR + 把「会怎样」写出来：读日志的人要能立刻知道
             *   「此刻审核还能不能跑」，而不是只知道「reload 失败了」。
             * ★ 完整栈进日志（第三个参数）；对外的 lastError 只有类名与一行摘要 ——
             *   同 P4 的防泄漏纪律：异常原文不进错误出口。
             */
            log.error("敏感词库快照重建失败 | 沿用上一份快照（若有）| 当前状态={} | 原因={}",
                    snapshotRef.get() == null ? "无快照，审核将被拒" : "有旧快照，审核继续用旧的",
                    lastError, e);
        }

        return status();
    }

    /* ==================== 状态 ==================== */

    /**
     * 当前状态 —— 管理页与验收脚本的读数口。
     *
     * <p>★★ <b>它会查一次库</b>（取 {@code enabledRows} / {@code totalRows}），这是刻意的：
     * 「库里有多少条」和「快照里有多少条」<b>不相等</b>正是最需要被看见的那个信号
     * （有人改了词但还没 reload）。只报快照一侧的话，管理页永远显示「一切正常」。
     *
     * <p>★ 查库失败时两个数是 {@code null} + {@code dbUnavailable=true}，<b>不是 0</b>：
     * 0 会被页面渲染成「库里一条词都没有」，而真相是「读不到」。
     * 本类刚论证过「空词库」和「读不到词库」必须分开 —— 这里是它的界面投影。
     *
     * <p>★ {@code lastError} <b>成功之后被清空</b>（在 {@link #reload()} 里）。
     */
    public SensitiveWordStatusVO status() {
        Snapshot snapshot = snapshotRef.get();

        SensitiveWordStatusVO vo = new SensitiveWordStatusVO();
        vo.setLoaded(snapshot != null);
        vo.setWordCount(snapshot == null ? 0 : snapshot.wordCount);
        vo.setVersion(snapshot == null ? 0 : snapshot.version);
        vo.setLoadedAt(snapshot == null ? null : snapshot.loadedAt);
        vo.setLastReloadAt(lastReloadAt);
        vo.setLastError(lastError);
        vo.setLastErrorAt(lastErrorAt);
        vo.setReady(snapshot != null && snapshot.wordCount > 0);

        try {
            Long enabled = sensitiveWordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery()
                    .eq(SensitiveWord::getEnabled, 1));
            Long total = sensitiveWordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery());
            vo.setEnabledRows(enabled == null ? 0 : enabled.intValue());
            vo.setTotalRows(total == null ? 0 : total.intValue());
            vo.setDbUnavailable(false);
        } catch (Exception e) {
            /*
             * ★★ 这里【不吞】：它是只读的展示口径，读到就读到，读不到就如实说读不到。
             *   ★ 关键是不能顺手填 0 —— 那是「看起来正常的假数据」，
             *     而它偏偏出现在一个专门用来「让陈旧可见」的页面上。
             */
            vo.setEnabledRows(null);
            vo.setTotalRows(null);
            vo.setDbUnavailable(true);
            log.warn("查询词库行数失败，状态接口按「读不到」返回（不是 0 条）| 原因={}: {}",
                    e.getClass().getSimpleName(), brief(e.getMessage()));
        }

        return vo;
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 异常摘要素 —— <b>只取第一行并截断</b>。
     *
     * <p>★ 这是 P4 防泄漏纪律在本类的落点：异常原文可能很长、可能带上 SQL 片段，
     * 而它唯一的去处是管理端页面上的一行提示。完整栈在 {@code log.error} 里。
     */
    private static String brief(String message) {
        if (message == null) {
            return "(no message)";
        }
        int nl = message.indexOf('\n');
        String first = nl >= 0 ? message.substring(0, nl) : message;
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    /**
     * 一份快照。
     *
     * <p>★ {@code version} 与 {@code loadedAt} <b>一起</b>表达「这一份是第几代、什么时候的」——
     * 版本号给验收脚本断言「快照真的被替换过」（单调递增，比时间戳好断言），
     * 时间给管理员判断「这一份有多旧」。
     */
    private record Snapshot(Automaton automaton, int wordCount, int version, LocalDateTime loadedAt) {
    }

    /**
     * Aho-Corasick 自动机。<b>构建完成后不再被修改</b>，所以可以被多个线程同时读。
     *
     * <p>★ 为什么不用「对每个词跑一次 {@code String.indexOf}」：那是
     * O(词数 × 文本长度)，而这里是 O(文本长度 + 命中数)。★ 更关键的是它
     * <b>不随词库增长而变慢</b> —— 一张 10 万词的库和一张 10 词的库，扫同一段正文耗时一样。
     * 在「词库是运营会持续往里加东西的表」这个前提下，那才是正确的形状。
     *
     * <p>★ 用 {@link HashMap} 存子节点而不是定长数组：中文词的字符集是整个 BMP，
     * 每个节点开一个 65536 长的数组会瞬间吃光内存 —— 这是中文场景与英文场景
     * 最经典的一个分岔，照抄英文实现会踩到。
     */
    private static final class Automaton {

        /**
         * 一个状态。
         *
         * <p>★★ <b>字段是「构建期可变、发布后不再改」的，而不是 {@code final} 的</b>，
         * 这一点要说清楚：自动机的 {@code fail} 指针只能<b>先建节点、后补指针</b>
         * （指向的目标必须先存在），所以没法写成不可变对象。
         * ★ 运行期的安全性由<b>发布方式</b>保证，不是由字段修饰符保证：
         * 构建全部完成后才 {@code AtomicReference.set()}，
         * 读线程 {@code get()} 拿到的是一个 <b>happens-before 已建立</b>的完整图。
         * <b>唯一的前提是 build() 返回之后没有任何人再碰这些字段</b> ——
         * 所以本类是 private 的、只被 {@code Scanner#reload} 调用一次一处。
         */
        private static final class Node {
            /** 字符 → 下一个状态 */
            private final Map<Character, Node> next = new HashMap<>(4);
            /** {@code 0} = 不是任何词的结尾；{@code 1~3} = 是某个词的结尾，且等级如此 */
            private int level;
            /** 结尾时记录<b>原词</b>（用来报「命中了哪个词」） */
            private String word;
            /** 结尾时记录分类（只给运营看，不参与判定） */
            private String category;
            /** ★ 失败指针。根节点的这一项永远是 {@code null} —— {@link #step} 靠它判断「退到头了」 */
            private Node fail;
        }

        private final Node root;
        private final int wordCount;

        private Automaton(Node root, int wordCount) {
            this.root = root;
            this.wordCount = wordCount;
        }

        Node root() {
            return root;
        }

        /**
         * 走一步：从 {@code from} 读入 {@code c}，返回落到的状态。
         *
         * <p>★ 先在当前状态找子节点，找不到就<b>沿失败指针退</b>，退到根为止。
         * ★★ 根的那一步必须特判：{@code root.fail} 是 {@code null}，不特判就是一次 NPE
         * —— 而它在「所有词的首字都对不上」时<i>必然</i>发生，
         * 也就是<b>一段完全干净的文字会把它炸出来</b>，正好把最该走通的那条路炸掉。
         */
        Node step(Node from, char c) {
            Node cur = from;
            while (cur != root && !cur.next.containsKey(c)) {
                cur = cur.fail;
            }
            Node next = cur.next.get(c);
            return next == null ? root : next;
        }

        /**
         * 把一批词装进自动机，并补全失败指针。
         *
         * <p>★ 空词直接跳过 —— 空串在自动机里无法表示（它会让<b>根节点变成终结节点</b>，
         * 于是任意文本的第一个字符就算命中，而日志上一切正常）。
         * ★ 入库前 {@code @NotBlank} 已经挡过一次，这里是第二道：
         * <b>「一个空词把全站内容判成高危」的代价太大，不值得只靠一道远处的校验。</b>
         *
         * @return 自动机。★ 返回之后调用方<b>不许</b>再接触任何 {@code Node} 的字段
         */
        static Automaton build(List<SensitiveWord> words) {
            // ---------- ① 建 Trie ----------
            Node root = new Node();
            int count = 0;
            for (SensitiveWord sw : words) {
                String word = sw.getWord() == null ? "" : sw.getWord().trim();
                if (word.isEmpty()) {
                    log.warn("词库里有一条空词被跳过（id={}）—— 空词会让任意文本的首字算命中", sw.getId());
                    continue;
                }
                count++;
                Node cur = root;
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    Node child = cur.next.get(c);
                    if (child == null) {
                        child = new Node();
                        cur.next.put(c, child);
                    }
                    cur = child;
                }
                /*
                 * ★ 后者覆盖前者：同一条词不会重复（uk_word 挡着），
                 *   所以这里到不了「两个词共用同一个终结节点」的情况。
                 */
                cur.level = sw.getLevel() == null ? 1 : sw.getLevel();
                cur.word = word;
                cur.category = sw.getCategory();
            }

            /*
             * ---------- ② BFS 补失败指针 ----------
             * ★ 必须是 BFS 而不是 DFS：失败指针指向的状态一定【更浅】，
             *   BFS 保证「指向的那个节点已经处理完了」，而 DFS 不保证 ——
             *   写成 DFS 的症状是随词库内容而变的偶发空指针，测试里很难复现。
             */
            Deque<Node> queue = new ArrayDeque<>();
            for (Node child : root.next.values()) {
                child.fail = root;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                Node cur = queue.poll();
                for (Map.Entry<Character, Node> entry : cur.next.entrySet()) {
                    char c = entry.getKey();
                    Node child = entry.getValue();

                    /*
                     * ★★ 这就是 AC 自动机的核心一步：孩子的失败指针 =
                     *   「父亲的失败指针能不能吃下这个字符」。
                     *   一直往上退到能吃下的那一层，吃不下就落到根。
                     *   ★ 注意退到根时【还是要试一次 root.next】：
                     *     漏掉这一步，所有词的首字都会被误判成「只能回到根」，
                     *     症状是「第二个字开始才可能命中」—— 一段只含一个词的文本扫不出来。
                     */
                    Node f = cur.fail;
                    while (f != null && !f.next.containsKey(c)) {
                        f = f.fail;
                    }
                    child.fail = (f == null) ? root : f.next.get(c);
                    queue.add(child);
                }
            }

            return new Automaton(root, count);
        }
    }
}
