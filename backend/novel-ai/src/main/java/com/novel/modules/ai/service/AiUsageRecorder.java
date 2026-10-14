package com.novel.modules.ai.service;

import com.novel.modules.ai.entity.AiUsageLog;
import com.novel.modules.ai.enums.AiFailure;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.mapper.AiUsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 调用记账（E1）。
 *
 * <h3>★★★ 这个类只有一条硬约束：<b>它永远不抛异常</b></h3>
 *
 * <p>记账是<b>附带</b>的事，生成才是主事。让「写流水失败」把一次成功的生成
 * 变成一个错误帧，是<b>用小事故毁掉大功能</b> —— 用户看到「生成失败」，
 * 而内容其实已经生成好了（只是没入库，因为流的出口已经关了）。
 *
 * <p>★ 所以这里 catch 一切，只留一条 ERROR 日志。
 * ★★ 但要说清楚代价：<b>吞掉异常等于让「账本没写进去」变成一个静默的事件</b>。
 * 处置是把它记成 ERROR（而不是 WARN/DEBUG）——
 * 记账失败是<b>要人去查</b>的：它意味着用量数据从此不可信，
 * 而不可信的账本比没有账本更糟（会让人做出错误的判断）。
 *
 * <h3>★★ 为什么是具体类而不是「接口 + Impl」</h3>
 *
 * <p>本项目大部分 Service 是「接口 + 实现」，那个形状是为<b>替换实现</b>服务的。
 * 这个类没有任何第二个实现的可能（它就是往一张表写一行），
 * 给它配一个接口只会多一个文件和一次跳转。
 * ★ 同 {@code AiCrypto} / {@code AiModelClient}：<b>「有第二个实现」才是接口的理由，
 * 「别人都这么写」不是。</b>
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecorder {

    /**
     * 身份缺失时写进 {@code user_id} 的哨兵值。
     *
     * <p>★★ 为什么不是「跳过这一行」：那样这笔消耗会<b>从账本上消失</b>，
     * 而钱是真的花了。<b>一个来源不明的数字远好过一个不存在的数字</b> ——
     * 前者会有人来问，后者永远不会被发现。
     * ★ {@code sys_user.id} 从 1 开始，所以 0 不可能是真实用户，
     * 它不会被误读成「某个人花的」。
     */
    public static final long UNKNOWN_USER_ID = 0L;

    private final AiUsageLogMapper usageLogMapper;

    /**
     * 一次调用的账。
     *
     * <p>★ 用 record 而不是八个位置参数：这里面有<b>两个相邻的 {@code Long}</b>
     * （{@code novelId} / {@code chapterId}），位置参数迟早会被写反，
     * 而写反的后果是「用量记到了另一本书上」—— <b>不报错、也能查</b>，
     * 只是查出来的答案是错的。
     */
    public record Entry(Long userId, AiScene scene, Long novelId, Long chapterId,
                        String model, AiChatUsage usage, long durationMs, AiFailure failure) {
    }

    /**
     * 记一笔。<b>成功、失败、被中断，三种都要记。</b>
     *
     * @param entry 见 {@link Entry}；{@code failure} 为 {@code null} 表示成功
     */
    public void record(Entry entry) {
        record(entry, entry.scene() == null ? null : entry.scene().getCode());
    }

    /** Scene 7 is reader book finding; it has file prompts, not an author template. */
    public void recordBookFinder(Entry entry) {
        record(entry, 7);
    }

    private void record(Entry entry, Integer sceneCode) {
        try {
            AiUsageLog row = new AiUsageLog();
            Long userId = entry.userId();
            if (userId == null) {
                /*
                 * ★★ 走到这里说明身份没被搬到工作线程上（见 AiController#stream 里
                 *   UserContext.set 那一段）。那是一个 bug，但它【不是】丢掉这笔账的理由。
                 * ★ 只记 ERROR 不抛：抛了也救不回身份，只会把生成搞挂。
                 */
                log.error("AI 记账时拿不到用户身份（UserContext 没搬到工作线程？）| scene={} | 本次记到 userId={}",
                        entry.scene() == null ? null : entry.scene().getCode(), UNKNOWN_USER_ID);
                userId = UNKNOWN_USER_ID;
            }
            row.setUserId(userId);
            row.setScene(sceneCode);
            row.setNovelId(entry.novelId());
            row.setChapterId(entry.chapterId());
            row.setModel(entry.model());

            AiChatUsage usage = entry.usage() == null ? AiChatUsage.ZERO : entry.usage();
            row.setPromptTokens(usage.promptTokens());
            row.setCompletionTokens(usage.completionTokens());
            row.setTotalTokens(usage.totalTokens());

            row.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, entry.durationMs())));
            /*
             * ★★ error_msg 只能来自枚举，见 AiFailure 的类注释。
             *   这一行是那条纪律【唯一】的落点 —— 它下面没有第二个写 error_msg 的地方。
             */
            row.setSuccess(entry.failure() == null ? 1 : 0);
            row.setErrorMsg(entry.failure() == null ? null : entry.failure().getCode());

            usageLogMapper.insert(row);

            log.debug("AI 记账 | user={} | scene={} | tokens={}/{}/{} | {}ms | {}",
                    userId, row.getScene(), usage.promptTokens(), usage.completionTokens(),
                    usage.totalTokens(), row.getDurationMs(),
                    entry.failure() == null ? "成功" : entry.failure().getCode());
        } catch (Exception e) {
            /*
             * ★ 见类注释：吞掉，但记成 ERROR。
             * ★ 异常本身【照记】（它是我们自己的 SQL 异常，不含上游内容），
             *   但【绝不】把它写进 error_msg 那一列 —— 那一列只接受 AiFailure。
             */
            log.error("AI 调用流水写入失败（账本不完整，用量数据从此不可信）| scene={} | failure={}",
                    entry.scene() == null ? null : entry.scene().getCode(),
                    entry.failure() == null ? "成功" : entry.failure().getCode(), e);
        }
    }
}
