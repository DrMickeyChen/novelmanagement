package com.novel.modules.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型对「违禁审核」那一问的答复解析成结构化判定。<b>纯函数，不碰库、不记日志以外的副作用。</b>
 *
 * <p>模板（种子 scene=5）要求它输出：
 * <pre>
 *   {"riskLevel": 0到3的整数, "hitWords": ["命中的词"], "comment": "判定理由"}
 * </pre>
 *
 * <h3>★★★ 解析器必须能剥 &#96;&#96;&#96;json 围栏 —— 这是真实模型的常态，不是异常</h3>
 *
 * <p>提示词里写了「只输出 JSON」，而<b>真实模型极常仍然裹一层 markdown 代码围栏</b>
 * （E1 的真实调用预算里，R2 那一次专门用来确认这件事）。
 * 只认「第一个字符是 <code>{</code>」的解析器在假端点上<b>永远验不出问题</b>
 * （假端点返回的是我们以为的形状），一接真模型就整条审核链哑掉 ——
 * 而症状是「一条 {@code ai_audit_result} 都没有」，看起来像消息没投出去。
 * 所以这里先剥围栏、再退一步取最外层的一对花括号。
 *
 * <h3>★★ 为什么失败要【抛异常】而不是返回一个「保守的默认判定」</h3>
 *
 * <p>任何默认值都会把「没解析出来」变成一次判定，而
 * {@code ai_audit_result.risk_level} 是 {@code NOT NULL} 且没有「未知」取值 ——
 * 这一列在结构上容不下「没审成」（见 {@link com.novel.modules.ai.entity.AiAuditResult} 的类注释）。
 * ★ <b>「没审成」只能表达为「没有这一行」</b>，所以解析失败必须一路抛到调用方，
 * 由它决定 ACK 且不写行。返回 {@code riskLevel=0} 是最坏的一种：
 * 那是把「审核失败」渲染成了「审核通过」。
 *
 * <p>★ <b>异常类型是自定义的</b>（不是 {@code IllegalArgumentException}）：
 * 调用方要靠它把「模型返回了看不懂的东西」与「我们自己的代码出错」分开 ——
 * 前者是<b>永久失败</b>（重试会重复烧钱、结果一样），后者才是 bug。
 * 用 JDK 自带异常的话，那个 {@code catch} 就得同时接住 NPE。
 *
 * @author MickeyChen
 */
@Slf4j
public final class AiAuditVerdict {

    private AiAuditVerdict() {
    }

    /** 风险等级的取值上限，与 {@code ai_audit_result.risk_level} 的刻度（0~3）一致 */
    public static final int MAX_RISK_LEVEL = 3;

    /**
     * 解析结果。
     *
     * @param riskLevel 0 无风险 1 低 2 中 3 高。★ 已夹到 {@code 0..3}
     * @param hitWords  模型说命中的词。★ <b>只用于日志与人工对照，落库的 {@code hit_words}
     *                  取我们自己的词库扫描结果</b>（理由见调用方）
     * @param comment   判定理由，可能为 {@code null}
     */
    public record Parsed(int riskLevel, List<String> hitWords, String comment) {
    }

    /** 「模型返回的东西解析不出判定」。★ 调用方按【永久失败】处置：ACK + 不写行 */
    public static class UnparsableException extends RuntimeException {
        public UnparsableException(String message) {
            super(message);
        }
    }

    /**
     * 解析。
     *
     * @param raw 模型返回的原文（<b>可能裹着围栏、可能前后有废话</b>）
     * @throws UnparsableException 见类注释。★ 它<b>不</b>是「上游调用失败」——
     *                             上游调成功了、钱已经花了、只是答复没法用
     */
    public static Parsed parse(ObjectMapper objectMapper, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new UnparsableException("模型返回了空内容");
        }

        String json = extractJsonObject(raw);
        if (json == null) {
            throw new UnparsableException("模型返回里找不到 JSON 对象（前 120 字：" + head(raw) + "）");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new UnparsableException("JSON 语法错误（前 120 字：" + head(raw) + "）");
        }

        JsonNode levelNode = node.get("riskLevel");
        if (levelNode == null || levelNode.isNull()) {
            /*
             * ★ 缺 riskLevel 一律算失败，【不】当作 0。
             *   模型偶尔会把字段名写成 risk_level / riskLevel2 —— 那些都是「这次没判定」。
             */
            throw new UnparsableException("JSON 里没有 riskLevel 字段（字段有：" + fieldNames(node) + "）");
        }

        int level = asInt(levelNode);
        if (level < 0 || level > MAX_RISK_LEVEL) {
            /*
             * ★★ 越界的方向是【唯一】需要想一下的地方：夹到 3 而不是退回 0。
             *   审核系统的失败方向必须是 fail-closed —— 模型说了个我们看不懂的等级（比如 5），
             *   把它当成「最高风险，人工看一眼」远好过当成「无风险，放过去」。
             *   ★ 记 WARN 而不是 ERROR：它没有让任何东西停下，只是这一次的等级需要人来读原文。
             */
            log.warn("AI 审核返回的 riskLevel 越界，已按最高风险处理 | raw={} level={}", head(raw), level);
            level = MAX_RISK_LEVEL;
        }

        return new Parsed(level, asStringList(node.get("hitWords")), asText(node.get("comment")));
    }

    /**
     * 从原文里取出那个 JSON 对象。
     *
     * <p>两步，顺序不能反：
     * <ol>
     *   <li>剥 markdown 代码围栏 —— 取第一对 &#96;&#96;&#96; 之间的内容（有语言标注也认，
     *       例如 &#96;&#96;&#96;json）；</li>
     *   <li>取<b>最外层</b>的一对花括号 —— 模型爱在 JSON 前后各加一句
     *       「好的，我来审核：」「以上是我的判断」。</li>
     * </ol>
     * ★ 用「第一个 <code>{</code> 到最后一个 <code>}</code>」而不是「配平括号」：
     * 配平要处理字符串里的花括号（判定理由里出现 <code>{</code> 是常事），
     * 而 {@code readTree} 自己就会在语法错误时抛异常 —— <b>多写一段配平逻辑，
     * 收益只是把错误从「语法错误」变成「找不到对象」，没有区别。</b>
     */
    private static String extractJsonObject(String raw) {
        String text = raw.trim();

        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int bodyStart = text.indexOf('\n', fenceStart);
            if (bodyStart > 0) {
                int fenceEnd = text.indexOf("```", bodyStart);
                // ★ 只有后半截围栏也在时才用它 —— 模型被 max_tokens 截断时可能只有开头那三个反引号
                if (fenceEnd > bodyStart) {
                    text = text.substring(bodyStart + 1, fenceEnd).trim();
                }
            }
        }

        int start = text.indexOf('{');
        // ★ lastIndexOf 而不是再找一个 '}'：理由见方法注释
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /**
     * 取值转 int。
     *
     * <p>★ 数字字符串也认（{@code "riskLevel": "2"}）：真实模型在「只输出 JSON」的
     * 提示下仍然时不时把数字写成字符串，而我们没有任何办法在提示词里彻底禁掉它 ——
     * <b>与其让整条审核链因为一个引号哑掉，不如认它。</b>
     * ★ 其余情况（{@code "high"} 这种）返回 {@code Integer.MIN_VALUE}，
     * 让调用方按「越界」处置 —— 那不是「没判定」，是「判了个我们不懂的等级」。
     */
    private static int asInt(JsonNode node) {
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = asText(node);
        if (text != null) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // 落到下面的哨兵值
            }
        }
        return Integer.MIN_VALUE;
    }

    private static List<String> asStringList(JsonNode node) {
        List<String> words = new ArrayList<>();
        if (node == null || !node.isArray()) {
            // ★ 不是数组就当「没给」：模型写成 "hitWords": "刀,枪" 时不必失败 ——
            //   这个词表本来就不落库（见 Parsed#hitWords）
            return words;
        }
        for (JsonNode item : node) {
            String word = asText(item);
            if (word != null && !word.isBlank()) {
                words.add(word.trim());
            }
        }
        return words;
    }

    private static String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(",", names);
    }

    /**
     * 日志里用的原文片段。
     * <p>★ 截断到 120 字：一次越界判定的原文可能是几千字的正文复述，
     * 整条打进日志会把那一段刷满，而排查要看的是<b>头 120 字</b>（形状就在那儿）。
     */
    private static String head(String raw) {
        if (raw == null) {
            return "null";
        }
        String oneLine = raw.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
