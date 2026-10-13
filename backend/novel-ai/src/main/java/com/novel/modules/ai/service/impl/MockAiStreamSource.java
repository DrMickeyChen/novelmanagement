package com.novel.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.service.AiStreamSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 的<b>演示实现</b>：不调用任何模型，按场景产出结构合理的假数据。
 *
 * <p><b>★★★ 这个类最要紧的一句话：它产出的内容是假的，但它是【真流式】的。</b>
 *
 * <p>它逐字推送、有延时、可被中断、会正常结束也会中途报错 —— 和接上真实模型
 * 之后的行为<b>在协议层完全一致</b>。所以前端那套东西（打字机效果、
 * 逐块拼接、中断、{@code [DONE]} 判定、错误帧处理）在这一期就被<b>真的测到</b>了，
 * 而不是等 E1 接上模型时才第一次运行。
 *
 * <p><b>★ 什么时候会被换成真实实现：</b>
 * 用配置 {@code novel.ai.provider} 切换（见类上的 {@code @ConditionalOnProperty}）：
 * <pre>
 *   novel.ai.provider: deepseek  → E1 的 DeepSeekAiStreamSource（★ 现在是默认）
 *   novel.ai.provider: mock      → 本类（必须显式选，用于零成本开发/演示）
 * </pre>
 *
 * <p><b>★★★ 下面这段是【被推翻的论证】，原文保留</b> —— 它回答「当初为什么默认 mock」：
 * <blockquote>
 * {@code matchIfMissing = true} 是有意的：没配这个值时用 mock，
 * 于是<b>新克隆下来的仓库不配任何东西就能跑起来</b>。
 * 反过来（默认真实实现）的话，没配 API Key 的人会看到 AI 面板一用就报错，
 * 而错误信息是「尚未配置 API Key」—— 对一个只是想跑起来看看的人来说，
 * 这个门槛完全没必要。
 * </blockquote>
 *
 * <p>★ 推翻它的是「这个系统已经做完，AI 就该是真 AI」这条判断，
 * 完整记账在 {@code application.yml} 的 {@code novel.ai.provider} 那一段。
 * <b>上面预言的症状会真的发生，而且是被刻意接受的代价</b>：新克隆的仓库不配 Key 时，
 * AI 面板返 5002。★ 但损失的【只有 AI 面板】—— 应用照常启动，浏览/写作/审核都可用，
 * 因为 {@code ai_config.enabled} 的种子本来就是 0。
 *
 * <p>★★ 所以本类现在【没有】{@code matchIfMissing}：配置项整个缺失时不再兜底到假数据，
 * 兜底挪给了 {@link DeepSeekAiStreamSource}。这条很关键 —— 留着它就会造出
 * 「配置项缺失 → 实际跑 mock，而 {@code AiPreflight#spendsMoney} 按 deepseek 报『会花钱』」
 * 这种两边不一致的状态。四份拷贝的同步要求见 {@code application.yml}。
 *
 * <p><b>★ 产出的内容不是 lorem ipsum</b>，每个场景给的是<b>结构对得上</b>的东西：
 * <table border="1">
 *   <tr><th>场景</th><th>产出</th><th>为什么是这个形状</th></tr>
 *   <tr>
 *     <td>outline</td>
 *     <td><b>严格合法的 JSON 树</b></td>
 *     <td>前端「流式收 JSON → 解析 → 长成节点 → 采纳落库」这条链路
 *         只有在 JSON 真的合法时才测得到。给一段散文的话，
 *         前端那套解析代码直到 E1 才第一次跑</td>
 *   </tr>
 *   <tr><td>write</td><td>小说散文</td><td>验证打字机效果和「追加到正文末尾」</td></tr>
 *   <tr><td>inspire</td><td>3 个剧情走向</td><td>验证长文本的分段展示</td></tr>
 *   <tr><td>polish</td><td>改写后的段落</td><td>验证「替换选中的那段」</td></tr>
 *   <tr>
 *     <td>summary</td>
 *     <td>一段剧情梗概，<b>开头一行自报「这是演示数据」</b></td>
 *     <td>★★ 它是<b>唯一一个产出会【不经采纳】直接落库</b>的场景，
 *         见 {@link #summary} —— 假数据在那里会以真前情的身份被喂回给真实模型</td>
 *   </tr>
 * </table>
 *
 * <p><b>★★ 关于「内容从哪来」：</b>
 * 本类<b>不会</b>真的理解输入 —— 它做不到，也不该假装做到。
 * 但它也不是「不管输入什么都返回同一段」：write 和 polish 会按输入的哈希
 * 从几段备选里挑一段、polish 还会把输入的开头接上去。
 * 这么做的理由是<b>演示</b>：连点两次「续写」得到一模一样的三段文字，
 * 看的人第一反应是「按钮坏了」。内容随机一点，交互是否正常才看得出来。
 *
 * <p><b>★ 一个测试钩子：</b>prompt <b>或 context</b> 里含 {@value #FAIL_MARKER} 时，
 * 本类会在生成到约三分之一处调用 {@code onError} ——
 * 用来验证「流中途失败时前端显示『生成失败』而不是永远转圈」
 * （负向用例 19）。这个标记<b>只在本类里存在</b>，
 * E1 的真实实现不认它，所以它不会变成一条需要长期维护的暗规则。
 * ★★ 要同时看两个字段的理由见 {@link #hasFailMarker}：<b>摘要场景的 prompt
 * 是服务端填的</b>，只认 prompt 就等于摘要没有失败分支可测。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "novel.ai.provider", havingValue = "mock")
public class MockAiStreamSource implements AiStreamSource {

    /**
     * 中途失败的测试钩子。★ 只用于本地验证错误分支，正常使用时不会出现。
     *
     * <p>用「prompt 里含某个字符串」而不是加一个 {@code ?mockFail=true} 参数：
     * 后者会把 mock 专用的东西写进<b>正式接口的签名</b>里，
     * 而这个 param 在 E1 之后毫无意义，却会一直留在文档上。
     */
    public static final String FAIL_MARKER = "__MOCK_FAIL__";

    /** 每推送一个字符后停多久。★ 这是「打字机效果」的来源，不是性能问题 */
    private final long delayMs;

    private final ObjectMapper objectMapper;

    public MockAiStreamSource(@Value("${novel.ai.mock-delay-ms:15}") long delayMs,
                              ObjectMapper objectMapper) {
        this.delayMs = delayMs;
        this.objectMapper = objectMapper;
        log.info("★★ AI 当前使用【演示模式】(MockAiStreamSource) —— 内容由本地生成，不是真实模型。"
                + "逐字间隔={}ms。★ 这条日志只在【显式】指定 novel.ai.provider=mock 时才会出现；"
                + "默认（deepseek）下本类不会被创建，所以看到它 = 有人主动切回了演练模式", delayMs);
    }

    @Override
    public void stream(AiScene scene, AiRequestDTO request, Handler handler) {
        long start = System.currentTimeMillis();
        String text = generate(scene, request);

        /*
         * ★ 这里能读到用户，靠的是【调用方】把身份搬到了本线程上 ——
         *   本方法跑在 ai-stream-N 线程（不是请求线程），
         *   ThreadLocal 在这是空的。见 AiController#stream 里的
         *   UserContext.set + finally clear。
         *
         * ★ 也就是说：如果哪天有人觉得那两行「多余」把它删了，
         *   本方法不会报错，只会安静地打出 user=null。
         */
        log.info("AI 生成开始 | scene={}({}) | user={} | 长度={}字 | 演示数据",
                scene.getCode(), scene.getLabel(), UserContext.getUsername(),
                text.codePointCount(0, text.length()));

        /*
         * ★ 有的场景生成到一半要「失败」，用来验证前端的错误分支。
         *   按【码点】数的三分之一，不是按 char 数 —— 两者在中英混排的文本里
         *   差得不小，而这里只需要一个「大约三分之一」的位置。
         */
        int totalCodePoints = text.codePointCount(0, text.length());
        int failAfter = hasFailMarker(request) ? Math.max(1, totalCodePoints / 3) : -1;

        int emitted = 0;

        /*
         * ★★ 按【码点】遍历，不是按 char。
         *
         *   Java 的 String 是 UTF-16，一个「字符」（码点）可能占两个 char
         *   （代理对），比如 emoji、部分生僻汉字。按 char 切会把一个码点
         *   从中间劈开，切出来的半个代理对不是合法字符 ——
         *   它会编码成 '?'，用户看到的是一个乱码方块。
         *
         *   中英混排的小说正文里出现这类字符完全正常（一个表情符号、
         *   一个生僻字），而且症状是「偶尔有一个字变成问号」——
         *   极难归因。所以这里用 codePointAt + charCount 老老实实走。
         */
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);

            /*
             * ★ 这里【故意的】不 try-catch：客户端断开之后，调用方的 onChunk
             *   会抛异常，让它把整个循环冲掉 —— 那正是「用户点了中断，
             *   服务端就停止生成」的实现方式。见 AiStreamSource#stream 的约定 2。
             */
            handler.onChunk(text.substring(i, i + charCount));

            i += charCount;
            emitted++;

            if (failAfter > 0 && emitted >= failAfter) {
                log.warn("AI 生成中途失败（测试钩子 {}）| scene={} | 已推送={}字",
                        FAIL_MARKER, scene.getCode(), emitted);
                handler.onError(new IllegalStateException("演示模式：模拟上游模型连接中断"));
                return;
            }

            if (!sleepQuietly()) {
                // 线程被中断（多半是应用正在关闭）—— 静默退出，不当成生成失败
                log.debug("AI 生成被中断 | scene={} | 已推送={}字", scene.getCode(), emitted);
                return;
            }
        }

        handler.onComplete();
        log.info("AI 生成完成 | scene={}({}) | 耗时={}ms | 推送={}字",
                scene.getCode(), scene.getLabel(), System.currentTimeMillis() - start, emitted);
    }

    /* ==================== 各场景的产出 ==================== */

    private String generate(AiScene scene, AiRequestDTO request) {
        return switch (scene) {
            case OUTLINE -> outlineJson(request);
            case WRITE -> continueWriting(request);
            case INSPIRE -> inspire(request);
            case POLISH -> polish(request);
            /*
             * ★★★ E1 新增的这一支【故意是抛异常】，而且【故意不写 default】。
             *
             * AUDIT（违禁审核）不是流式场景：AiStreamSource 的契约
             * （恰好一次终结、必须可被打断）是为 SSE 连接的断开语义服务的，
             * 而审核没有连接可断 —— 它走 AiModelClient 的非流式调用。
             * 所以「审核走到流式 mock 里」本身就是一条不该存在的调用路径，
             * 撞上了就该炸，而不是给它编一段假审核结果。
             *
             * ★★ 为什么不留一个 default 分支（那才是「更简洁」的写法）：
             * 这个 switch 是【穷尽的】，而穷尽性正是它的价值 ——
             * E1 给 AiScene 加 AUDIT(5) 时，编译器在这里【报错】并指出文件行号，
             * 我们才知道有这么一处需要处理。一旦写成 default，
             * 下一个加进来的枚举值就会【静默地】落进 default，安静地跑错。
             * ★ 这是铁律 8 的反面用法：把「忘了一处」从运行时错误变成编译错误。
             */
            case AUDIT -> throw new IllegalStateException(
                    "AUDIT 不是流式场景，不该走到 MockAiStreamSource —— 见 AiScene.AUDIT 的注释");
            /*
             * ★ 摘要【是】流式场景（它走 AiStreamSource），只是调用方把结果
             *   收完再落库、不往前端推 —— 所以它在本类里是一支正常分支。
             *   ★★ 换句话说：AUDIT 那一支抛异常是因为「这条路不该存在」，
             *   而 SUMMARY 这一支必须存在，两者别混为一谈。
             */
            case SUMMARY -> summary(request);
        };
    }

    /**
     * 生成大纲 —— <b>严格合法的 JSON 树</b>。
     *
     * <p><b>★ 用 Jackson 序列化 {@code Map}，而不是用字符串拼 JSON。</b>
     * 拼字符串的话，用户输入的标题里一个引号或反斜杠就会产出非法 JSON ——
     * 而 mock 的产出是<b>要被前端的解析代码消费</b>的，
     * 一个「偶尔失败」的 mock 会让前端的 bug 和 mock 的 bug 混在一起，
     * 排查时根本分不清是谁的问题。交给 Jackson 就没有这一类问题。
     *
     * <p><b>★ 缩进输出是有意的：</b>流式效果在缩进 JSON 上才看得出来
     * （每一行都短、结构层次分明），压成一行的话界面上就是一条长条在滚。
     *
     * <p>结构对齐大纲 §8.2⑥：
     * <pre>
     *   {"title": "...", "children": [{"type": 3, "title": "...", "content": "..."}]}
     * </pre>
     * ★ 里面的 {@code type} <b>就是</b> {@code OutlineNodeType} 的 code
     * （3=章 4=细纲），所以前端「采纳」时可以直接把它填进新建节点的请求里，
     * 不需要一张「AI 的类型 → 我们的类型」的映射表。少一张映射表就少一个
     * 会腐烂的地方。
     */
    private String outlineJson(AiRequestDTO request) {
        String topic = topicOf(request.getPrompt());

        List<Map<String, Object>> children = new ArrayList<>();
        /*
         * ★★ 第一章的标题【不能】拼 topic。
         *
         *   原来写的是 `"第一章 " + topic`，而 topic 是从 prompt 里截出来的一句
         *   指令 —— 实测产出长这样：
         *     "title": "第一章 为「全书大纲」生成下一级大纲"
         *   它只在 prompt 恰好是个名词短语时才读得通，其余情况全是这个形状。
         *   ★ 改成 topicOf 取「」里的名字也治不好：那会变成「第一章 全书大纲」，
         *     而「全书大纲」是**父节点**的名字，用它当子章节名同样不通。
         *
         *   ★ 干脆给一个正常的章名，和下面两条保持一致。
         *     mock 对 prompt 的「回应」体现在 root.title 和每个节点的 content 上 ——
         *     章名本来就该是模型自己起的，不需要假装是从输入里抠出来的。
         */
        children.add(outlineNode(3, "第一章 开端", "开篇：用一个具体的场景把读者带进来，不要先介绍背景。"));
        children.add(outlineNode(3, "第二章 暗涌", "冲突浮出水面。让主角以为找到了答案，其实找到了更深的问题。"));
        children.add(outlineNode(3, "第三章 抉择", "主角必须在两个都有代价的选项里选一个，选完就没有退路。"));
        children.add(outlineNode(4, "伏笔：那句没说完的话", "留一处到了后文才回收的对话，回收时读者会想起这里。"));
        children.add(outlineNode(4, "节奏提示", "这三章跨度不宜太大，最好发生在同一天之内。"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", topic);
        root.put("children", children);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            /*
             * ★ 用 BusinessException(AI_CALL_FAILED) 而不是 UncheckedIOException：
             *   走到这里说明「生成」失败了，对用户来说和模型调用失败是同一件事，
             *   错误码 5003 前端已经在处理了。而 500 会让前端提示「服务器开小差了」，
             *   把一次生成失败说成了后端故障。
             */
            throw new BusinessException(ResultCode.AI_CALL_FAILED);
        }
    }

    private Map<String, Object> outlineNode(Integer type, String title, String content) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.put("title", title);
        node.put("content", content);
        return node;
    }

    /**
     * 续写正文。
     *
     * <p>★ 从三段备选里按 prompt 的哈希挑一段 —— 见类注释「关于内容从哪来」。
     * {@code Math.floorMod} 而不是 {@code %}：{@code hashCode()} 可能是负数，
     * 而负数取模的结果也是负数，会直接 <b>ArrayIndexOutOfBoundsException</b>。
     * 这是那种「测的时候一直是正数所以没发现」的坑。
     */
    private String continueWriting(AiRequestDTO request) {
        String[] passages = {
                """
                        雨还在下。

                        他站在檐下，看水痕沿着瓦沟淌下来，在青石板上砸出细小的白花。远处的灯笼被风吹得摇晃，光影落在湿漉漉的街面上，碎成一片。

                        「你迟到了。」身后的声音很轻，却让他脊背一紧。

                        他没有回头。有些话一旦转过身去说，就再也收不回来了。
                        """,
                """
                        灯芯爆了一下，屋子里暗了半瞬。

                        她把手里的信纸按在桌上，指节因为用力而发白。窗外的风穿过门缝，把纸角掀起又放下，像是在替谁犹豫。

                        「你早就知道了。」

                        不是问句。他沉默着，那种沉默比任何回答都更像承认。
                        """,
                """
                        山谷里起雾了，比预想的要早。

                        走在前面的人忽然停下，抬手示意。所有人都伏低了身子，只有风从他们头顶上掠过去，带着一股说不清的腥甜。

                        「不对劲。」

                        有人小声问怎么了。他没有回答，只是把手按在了刀柄上 —— 那个动作说明了一切。
                        """
        };
        return passages[Math.floorMod(String.valueOf(request.getPrompt()).hashCode(), passages.length)];
    }

    /** 三个剧情走向。★ 把用户的输入嵌进标题里，让「它确实读了我的话」看得出来 */
    private String inspire(AiRequestDTO request) {
        String topic = topicOf(request.getPrompt());
        return """
                【走向一 · 顺接】沿着「%s」往下走。
                让主角以为事情在变好，一步一步放松警惕，直到最后那一下才收网。读者的不安来自「明明一切都对，可就是有点不对」。

                【走向二 · 反转】把「%s」里最不起眼的那个人翻出来。
                前面所有看似无意义的细节都是他做的，重读时每一句都能对上。风险是反转太晚读者会觉得被骗，所以要在中段留一次提醒。

                【走向三 · 留白】不解决，直接跳过。
                下一章开头已经是三个月后，结果用别人的嘴说出来。省下的篇幅用来写主角身上那处没人注意到的新伤 —— 读者会自己补上中间发生了什么。
                """.formatted(topic, topic);
    }

    /**
     * 润色段落。
     *
     * <p>★ 把输入的<b>开头一段</b>接在改写前面，是为了让输出看起来
     * 「确实是在改这段话」而不是一段无关的文字。真实的改写做不到，
     * 但演示时如果没有这层关联，看的人会以为接口串了。
     */
    private String polish(AiRequestDTO request) {
        String opening = truncateByCodePoint(String.valueOf(request.getPrompt()).trim(), 14);
        return opening + "——" + """
                他推门走进夜色里，天幕沉沉，一丝光也没有。

                风裹着湿气从巷口灌进来，吹得他衣摆翻卷。他抬手按住了怀里的东西，那点温度还在，隔着布料一下一下地跳。

                身后没有人跟上来。他知道，但也知道这只是暂时的。
                """.trim();
    }

    /**
     * 前情摘要 —— ★★ <b>唯一一个把「这是演示数据」写进产出里的场景。</b>
     *
     * <p><b>为什么这里非要自我标识，而其它四个场景不用：</b>
     * 大纲 / 正文 / 灵感 / 润色的产出都要作者点一次「采纳」才落库 ——
     * 一段假数据最坏的结果是被人看一眼丢掉，<b>不会进数据库</b>。
     * 摘要<b>没有「采纳」这一步</b>：生成完直接写 {@code ai_summary}，
     * 下一章的 {@code {{summary}}} 就会把它当成「更早的剧情」喂回去。
     * 于是它<b>会以真前情的身份进入真实模型的上下文</b>，而界面上没有任何一处提示这件事。
     *
     * <p>★ 所以标记必须落在<b>产出正文里</b>，不能靠界面上的「演示模式」标签 ——
     * 那个标签只说明「现在这个进程不调真模型」，管不到「库里这条摘要是谁写的」；
     * 而一旦有人把 {@code novel.ai.provider} 切回 deepseek，
     * 那个标签就消失了，<b>而假摘要还留在库里继续被喂回去</b>。
     * 写进正文的标记则相反：它<b>跟着数据走</b>，只要那一行还在，
     * 「这条摘要不是真的」就是一句可查的事实，而不是要靠人记着的背景知识。
     *
     * <p>★ 按 context（要压缩的正文）的哈希挑一段，理由同 {@link #continueWriting}：
     * 连点两次得到一模一样的结果，看的人第一反应是按钮坏了。
     */
    private String summary(AiRequestDTO request) {
        String source = String.valueOf(request.getContext());
        String[] passages = {
                """
                主要人物在上一段里各自做出了一个不可逆的选择：一个把退路封死，
                另一个第一次说了实话，而第三个人什么都没说 —— 但正是那份沉默让前两个人
                意识到，他们以为的「共同目标」从一开始就不是同一个。

                局势上，明面上的冲突暂时平息，暗处那个一直没被点名的角色开始有了动作。
                主角手里多了一条当时看来无关紧要的线索，尚未意识到它的用处。""",
                """
                上一段以一次代价不小的胜利收尾：想做的事做成了，但失去的东西
                比预期多。主角对外表现得若无其事，独处时的反应才是真正的状态。

                值得注意的是两处伏笔 —— 一句被打断的话、一个当时没解释的动作 ——
                它们都还没有回收，后文需要回来交代。反派这一段的动机已经露出冰山一角，
                但真实目的仍被遮着。""",
                """
                这一段的主线是「靠近」：两个人从互相试探走到愿意交换一部分真话，
                各自保留的最后那点秘密成为下一段冲突的引信。

                支线上，配角的那条线第一次和主线交汇，交汇点是一个谁都没在意的细节。
                环境描写里出现过两次的那个意象应当在后文再次出现，用来标记时间的推移。"""
        };
        String body = passages[Math.floorMod(source.hashCode(), passages.length)];

        /*
         * ★ 顺带把「覆盖了多少字」写进产出：mock 读不懂正文，但它能证明
         *   正文【真的送到了】—— 这个数字与请求侧对不上（比如全是 0、
         *   或者明显小于章节字数之和），就说明组装那一段出了问题，
         *   而不是模型的问题。★ 和上面那行自我标识一样，都是「把内部状态
         *   变成一个能被看见的数字」那条思路。
         */
        return "【演示数据】以下梗概由 MockAiStreamSource 生成，未调用真实模型，"
                + "不可作为正式前情使用。本次压缩覆盖正文约 "
                + source.codePointCount(0, source.length()) + " 字。\n\n" + body;
    }

    /* ==================== 私有工具 ==================== */

    /**
     * 请求里有没有「中途失败」的测试钩子。
     *
     * <p>★★ 必须同时看 {@code prompt} 和 {@code context}。
     * 摘要场景的 {@code prompt} 是<b>服务端</b>填的（作者点的是一个按钮，
     * 按钮上没有文案可发），唯一的作者输入是 {@code context}（要压缩的正文）——
     * 只看 prompt 的话，<b>摘要就是唯一一个没法验证失败分支的场景</b>，
     * 而它恰恰是「生成失败但一行都不许写」这条规则最需要被证明的地方
     * （一条编出来的假摘要会污染后面所有章节）。
     *
     * <p>★ 反过来，让钩子的<b>触发条件变宽</b>是安全的：它只在演示实现里存在，
     * 且触发它需要请求里真的含那个魔法字符串 —— 不会有人误触。
     */
    private static boolean hasFailMarker(AiRequestDTO request) {
        return containsFailMarker(request.getPrompt()) || containsFailMarker(request.getContext());
    }

    private static boolean containsFailMarker(String text) {
        return text != null && text.contains(FAIL_MARKER);
    }

    /**
     * 从 prompt 里取一个能当标题的短句。
     *
     * <p>★ 一定要截断。真实模型不会把用户的整段输入当标题，
     * 但 mock 是直接拿的 —— 不截的话，用户贴 2000 字进来，
     * 大纲里就会长出一个 2000 字的节点标题，界面直接崩。
     *
     * <p>★ 测试钩子的标记要先去掉，否则它会出现在标题里 ——
     * 而那个标记是给错误分支用的，不该污染正常输出。
     */
    private static String topicOf(String prompt) {
        if (prompt == null) {
            return "未命名";
        }
        String cleaned = prompt.replace(FAIL_MARKER, "").trim();
        int firstLineBreak = cleaned.indexOf('\n');
        if (firstLineBreak > 0) {
            cleaned = cleaned.substring(0, firstLineBreak).trim();
        }
        if (cleaned.isEmpty()) {
            return "未命名";
        }
        /*
         * ★★ 先试「」里的那个名字。
         *
         *   前端发的 prompt 不是主题，是**一句指令**：
         *     `为「全书大纲」生成下一级大纲`
         *   直接截断的话，`root.title` 就成了整句指令（实测就是这样），
         *   在原始的流式 JSON 里一眼就能看到，看着像坏了。
         *
         *   而真实模型会读懂这句话，返回它自己起的名字而不是复述指令。
         *   我们的 prompt 模板一律用「」把主体括起来（见 `NodeWorkspace`
         *   的 `gatherAiRequest`），所以取引号里那个词最接近真实模型的行为。
         *
         *   ★ 取不到就退回截断，**不能假设「」一定在** ——
         *     用户从 Knife4j / curl 直接调这个接口时发什么都有可能。
         */
        int open = cleaned.indexOf('「');
        int close = open < 0 ? -1 : cleaned.indexOf('」', open + 1);
        if (close > open + 1) {
            String quoted = cleaned.substring(open + 1, close).trim();
            if (!quoted.isEmpty()) {
                return truncateByCodePoint(quoted, 16);
            }
        }
        return truncateByCodePoint(cleaned, 16);
    }

    /** 按码点截断，★ 不能直接 {@code substring(0, n)}：那样可能劈开代理对 */
    private static String truncateByCodePoint(String text, int maxCodePoints) {
        int count = text.codePointCount(0, text.length());
        if (count <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, endIndex) + "…";
    }

    /**
     * 睡一小会儿，让「逐字出现」看得见。
     *
     * @return {@code true} 正常睡完；{@code false} 线程被中断了（应用正在关闭）
     */
    private boolean sleepQuietly() {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            /*
             * ★ 必须把中断标志【放回去】。吞掉 InterruptedException 而不恢复标志
             *   是 Java 并发里最经典的错误之一：调用栈上层的代码再也看不到
             *   「有人要求我停下来」这个信号，于是关闭流程会一直等下去。
             *   项目里其它用 Thread.sleep 的地方也要守这条。
             */
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
