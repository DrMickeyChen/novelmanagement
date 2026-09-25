package com.novel.modules.ai.service.impl;

import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.service.AiAuditEvaluator;
import com.novel.modules.ai.service.AiAuditVerdict;
import com.novel.modules.ai.service.AiChatUsage;
import com.novel.modules.ai.service.AiUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 审核的<b>演示实现</b>：不调用任何模型，直接把判定定为「无风险」。
 *
 * <h3>★★★ 它【永远返回 riskLevel=0】—— 这一条是刻意的，而且理由不是「假数据」</h3>
 *
 * <p>违禁审核是<b>两层</b>的（关键词层 + AI 层），落库的等级取
 * {@code max(关键词层, AI 层)}。如果 mock 也返回一个非零等级，
 * <b>每一次验收跑出来的风险值都会带上一份假数据的贡献</b> ——
 * 于是「词库改了、等级变了」这件事在界面上<b>看不出来</b>，
 * 因为 AI 层那个常量把它盖住了。★ 那正是铁律 8 说的假阳性：
 * 一条<b>永远为真</b>的判据（等级永远 ≥ mock 那个值）。
 *
 * <p>归零之后，演示模式下<b>风险等级 100% 由词库决定</b>，
 * 「加一个词 → 命中 → 进队列」这条链在<b>不花一分钱</b>的情况下是可验的。
 *
 * <p>★★ 但「AI 层在这条链上确实被调用了」这件事不能因此隐身 ——
 * 它由<b>三个东西</b>同时证明：
 * <ol>
 *   <li>{@link #DEMO_COMMENT} 落进 {@code ai_audit_result.ai_comment}，页面上看得见；</li>
 *   <li>每次调用都往 {@code ai_usage_log} 记一行 {@code scene=5, model=mock}；</li>
 *   <li>日志里一行「AI 审核【演示模式】」。</li>
 * </ol>
 * ★ 这三条与 {@code MockAiStreamSource} 的处理是<b>同一个手法</b>：
 * 内容可以是假的，<b>但「谁在跑、跑了没有」必须是真的</b>，
 * 否则「切到真实模型」这件事就变成了一个没有对照组的开关。
 *
 * <h3>★ 一个测试钩子在哪 / 不在</h3>
 *
 * <p>{@code MockAiStreamSource} 有一个 {@code __MOCK_FAIL__} 钩子。
 * <b>本类刻意没有</b>：审核的失败分支（坏 JSON、上游 4xx、超时）要验的是
 * {@code AiAuditVerdict} 的解析与消费者的分流，而<b>构造一个坏模型答复最直接的办法
 * 是让假端点返回它</b>（{@code docs/tools/fake-openai.py} 的 {@code fake-garbage}）——
 * 那条路同时验到了 HTTP 层，比在 mock 里藏一个只有 mock 认的魔法字符串更接近真实故障。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "novel.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiAuditEvaluator implements AiAuditEvaluator {

    /** 写进 {@code ai_usage_log.model} 的取值。★ 用量页据此把演示数据与真实消耗分开 */
    public static final String MODEL = "mock";

    /**
     * 落进 {@code ai_audit_result.ai_comment} 的那句话。
     *
     * <p>★ 它必须<b>自报家门</b>：管理员在队列页上看到的是「AI 说无风险」，
     * 而那句话是本地编的 —— 不写出来，一次演示跑出来的结论会被人当成真实判定读。
     * ★ 后半句「关键词层的结果仍然有效」是准确性的关键：
     * 这一格是 0，但那一行的 {@code risk_level} 不是 0（它来自词库），
     * 两句话不放在一起会让人以为这一行「什么都没判」。
     */
    public static final String DEMO_COMMENT =
            "【演示模式】未调用真实模型，本条不含模型的语义判断；"
                    + "该行的风险等级与命中词均来自本地关键词词库。";

    private final AiUsageRecorder usageRecorder;

    public MockAiAuditEvaluator(AiUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
        log.info("★★ AI 违禁审核当前使用【演示模式】(MockAiAuditEvaluator) —— "
                + "AI 层固定判为无风险，风险等级完全由关键词词库决定。"
                + "接真实模型请设置 novel.ai.provider=deepseek");
    }

    @Override
    public AiAuditVerdict.Parsed evaluate(String systemPrompt, String userMessage,
                                          Long novelId, Long chapterId) {
        long start = System.currentTimeMillis();

        /*
         * ★ 日志里打出提示词的字数，而不是内容：待审正文是用户内容，
         *   整段进日志会把日志文件撑爆，也会让「日志里有没有违规内容」变成一个新问题。
         *   ★★ 但【字数】是有信息量的：它能证明「送进来的确实是正文本身、
         *   不是 {{selected}} 那个字面量」—— R2 风险清单里那条
         *   「模型审的是那 13 个字符 → 永远返回 riskLevel:0，界面一切正常」的
         *   手工对照就靠这一行。字面量是 13 个字，正文不会是。
         */
        log.info("AI 审核【演示模式】| novelId={} | chapterId={} | 待审正文={}字 | 判定=无风险",
                novelId, chapterId, codePointCount(userMessage));

        try {
            /*
             * ★ 不用 Jackson 拼一段假 JSON 再解析回来：
             *   那会让「演示模式」变成一条【依赖解析器正确】的路径 ——
             *   解析器哪天改坏了，默认配置下整个审核链跟着哑掉，
             *   而失败现场看起来像「模型返回了坏 JSON」。
             *   直接给结论，把「解析」这件事留给真的需要解析的那条路
             *   （deepseek + 假端点，见类注释）。
             */
            return new AiAuditVerdict.Parsed(0, List.of(), DEMO_COMMENT);
        } finally {
            /*
             * ★ 见类注释：这一行不是装饰 —— 它是「幂等闸门挡住了第二次调用」
             *   那条阳性对照唯一的载体（数 ai_usage_log 的行）。
             * ★ token 全 0 是准确的：没有真实消耗。用量页上一行
             *   model=mock / tokens=0/0/0 读作「跑过一次，没花钱」。
             * ★ user_id 见 AiUsageRecorder.UNKNOWN_USER_ID 的论证：
             *   这是一次系统行为，背后没有请求。
             */
            usageRecorder.record(new AiUsageRecorder.Entry(
                    AiUsageRecorder.UNKNOWN_USER_ID, AiScene.AUDIT, novelId, chapterId,
                    MODEL, AiChatUsage.ZERO, System.currentTimeMillis() - start, null));
        }
    }

    /** 按码点数，不是 {@code length()}：中英混排下两者差得不小（同 {@code MockAiStreamSource}） */
    private static int codePointCount(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
