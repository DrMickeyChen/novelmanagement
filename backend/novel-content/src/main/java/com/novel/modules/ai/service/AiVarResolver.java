package com.novel.modules.ai.service;

import com.novel.modules.ai.enums.AiPlaceholder;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.enums.AiVarRequirement;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 场景 × 占位符的叉乘表 —— <b>「哪个场景有哪些变量、哪些必填」的唯一真相来源</b>（E1）。
 *
 * <p>词汇表（有哪些占位符、各自从哪来）在 {@link AiPlaceholder}；
 * 这里只回答<b>「这个场景里它算哪一档」</b>。两者分开是因为它们是两个不同的问题：
 * 前者改一次影响全项目，后者改一次只影响一个场景。
 *
 * <h3>★★★ 判据：这里的每一格都必须能追溯到【实际客户端】的行为，不能凭空设计</h3>
 *
 * <p>这张表是照着 {@code NodeWorkspace.vue} 的 {@code gatherAiRequest}（实测）写的，
 * 不是照着 {@code AiRequestDTO} 的类注释 —— ★ 那两处<b>说的正好相反</b>，
 * 而注释安静地错了两期（见 {@code AiRequestDTO} 的类注释订正记录）。
 * <b>以代码为准。</b>
 *
 * <table border="1">
 *   <tr><th>场景</th><th>{@code {{request}}}</th><th>{@code {{selected}}}</th>
 *       <th>{@code {{novelTitle}}}</th><th>{@code {{outline}}}</th><th>{@code {{summary}}}</th></tr>
 *   <tr><td>OUTLINE</td><td><b>必填</b></td><td>不适用</td><td>可选</td><td>可选</td><td>可选</td></tr>
 *   <tr><td>WRITE</td><td>可选</td><td><b>必填</b></td><td>可选</td><td>可选</td><td>可选</td></tr>
 *   <tr><td>INSPIRE</td><td><b>必填</b></td><td>不适用</td><td>可选</td><td>可选</td><td>可选</td></tr>
 *   <tr><td>POLISH</td><td>不适用</td><td><b>必填</b></td><td>可选</td><td>不适用</td><td>不适用</td></tr>
 *   <tr><td>AUDIT</td><td>不适用</td><td><b>必填</b></td><td>不适用</td><td>不适用</td><td>不适用</td></tr>
 *   <tr><td>SUMMARY</td><td>不适用</td><td><b>必填</b></td><td>可选</td><td>不适用</td><td>可选</td></tr>
 * </table>
 *
 * <h3>★★ 三处「必填」的理由（这三格是这张表真正需要想清楚的地方）</h3>
 *
 * <ul>
 *   <li><b>OUTLINE / INSPIRE 的 {@code {{request}}} 必填</b>：节点身份<b>只在这句话里</b>
 *       （「为「X」生成下一级大纲」）。缺了它模型不知道该往哪个节点挂 ——
 *       而它其实<b>不可能缺</b>（{@code prompt} 是 {@code @NotBlank}），
 *       所以这一格是<b>声明式的</b>：它约束的是「模板必须有这一行」，
 *       而不是「运行时会缺」。</li>
 *   <li><b>WRITE / POLISH / AUDIT 的 {@code {{selected}}} 必填</b>：它是要处理的素材本身。
 *       ★ 缺了它模型不会报错，它会<b>自己编一段</b> —— 前端把编出来的东西显示成
 *       「润色结果」让作者采纳，<b>而额度已经花掉了</b>。
 *       ★★ 这一格是<b>真的会缺</b>的：POLISH 靠作者在正文里选中一段，
 *       而「没选中就点润色」是个再正常不过的误操作。</li>
 * </ul>
 *
 * <h3>★★ 与大纲的四处占位符不一致 —— 这是 E1 的订正 3</h3>
 *
 * <p>大纲 §8.2⑤ 写的是四个占位符且没有 {@code {{request}}}，
 * 而种子模板用的是 {@code {{novelTitle}} {{outline}} {{summary}} {{selected}}}。
 * ★ E1 补上第五个 {@code {{request}}} 的理由见 {@link AiPlaceholder#REQUEST}：
 * <b>没有它，OUTLINE 模板拿不到节点身份</b>，模型只能瞎猜往哪挂。
 * ★ 而 {{selected}} 的语义<b>维持原样</b>（「要处理的东西」），
 * 因为种子模板对它的措辞本来就和 {@code context} 逐行吻合。
 *
 * <p>★ <b>本类是纯静态的、不依赖 Spring</b>：它是一张<b>常量表</b>，不是服务。
 * 声明成 Bean 只会让渲染器多一个可以注入错东西的地方。
 *
 * @author MickeyChen
 */
public final class AiVarResolver {

    /** 场景 → （占位符 → 档位）。★ 用 EnumMap：键是枚举，没有哈希冲突且遍历有序 */
    private static final Map<AiScene, Map<AiPlaceholder, AiVarRequirement>> TABLE = buildTable();

    private AiVarResolver() {
    }

    private static Map<AiScene, Map<AiPlaceholder, AiVarRequirement>> buildTable() {
        Map<AiScene, Map<AiPlaceholder, AiVarRequirement>> table = new EnumMap<>(AiScene.class);

        /*
         * ★ 每个场景都把【五个占位符全部写出来】，包括 NOT_APPLICABLE 的那些。
         *   看着啰嗦，但这是刻意的：留白会让「这个场景没有这个变量」和
         *   「我忘了写这一格」在代码里长得一模一样。
         *   ★★ 而且管理端要靠它渲染「本场景可用变量」的提示 —— 缺格会让提示也缺一块。
         */
        table.put(AiScene.OUTLINE, scene(
                AiVarRequirement.REQUIRED,      // request  —— 节点身份只在这句话里
                AiVarRequirement.NOT_APPLICABLE,// selected —— 大纲场景不处理素材
                AiVarRequirement.OPTIONAL,      // novelTitle
                AiVarRequirement.OPTIONAL,      // outline  —— 该节点自己的标题与内容
                AiVarRequirement.OPTIONAL       // summary
        ));

        table.put(AiScene.WRITE, scene(
                /*
                 * ★ 这里是 OPTIONAL 而不是 REQUIRED，和 OUTLINE 不一样：
                 * WRITE 的 prompt 是「接着往下写「章节名」」，章节名是个补充信息 ——
                 * 真正的上下文在 {{selected}}（正文末尾）和 {{outline}}（本章大纲）里。
                 * 缺了它不影响模型知道该写什么。
                 */
                AiVarRequirement.OPTIONAL,      // request
                AiVarRequirement.REQUIRED,      // selected —— 没有正文就无从「续写」
                AiVarRequirement.OPTIONAL,      // novelTitle
                AiVarRequirement.OPTIONAL,      // outline  —— 走 chapterId，那一列可空
                AiVarRequirement.OPTIONAL       // summary
        ));

        table.put(AiScene.INSPIRE, scene(
                AiVarRequirement.REQUIRED,      // request  —— 同上，节点身份在它里面
                AiVarRequirement.NOT_APPLICABLE,// selected
                AiVarRequirement.OPTIONAL,      // novelTitle
                AiVarRequirement.OPTIONAL,      // outline
                AiVarRequirement.OPTIONAL       // summary
        ));

        table.put(AiScene.POLISH, scene(
                /*
                 * ★ request 在 POLISH 里是 NOT_APPLICABLE，因为它的 prompt 恒为
                 * 「润色下面这段文字」—— 一句固定的话，把它拼进提示词对模型毫无增量，
                 * 反而占了位置。这是四个生成场景里唯一一个 request 没有信息量的。
                 */
                AiVarRequirement.NOT_APPLICABLE,// request
                AiVarRequirement.REQUIRED,      // selected —— 选中的那段，缺了它没得润
                AiVarRequirement.OPTIONAL,      // novelTitle
                AiVarRequirement.NOT_APPLICABLE,// outline  —— 润色只看选中的文字，不看大纲
                AiVarRequirement.NOT_APPLICABLE // summary
        ));

        table.put(AiScene.AUDIT, scene(
                /*
                 * ★★ AUDIT 只有 {{selected}} 一个变量，这不是「还没做完」。
                 *   审核的判据必须【只看被判的文本本身】：把作品名、大纲、前情摘要
                 *   拼进去，会让模型拿「剧情里出现了一把刀」当违规证据 ——
                 *   而审核要判的是这段文字有没有问题，不是这个故事讲了什么。
                 *   ★ 少给它上下文，是审核这个场景的【正确性要求】，不是省事。
                 */
                AiVarRequirement.NOT_APPLICABLE,// request
                AiVarRequirement.REQUIRED,      // selected —— 待审正文
                AiVarRequirement.NOT_APPLICABLE,// novelTitle
                AiVarRequirement.NOT_APPLICABLE,// outline
                AiVarRequirement.NOT_APPLICABLE // summary
        ));

        table.put(AiScene.SUMMARY, scene(
                /*
                 * ★ request 在这里是 NOT_APPLICABLE，但理由和 POLISH 那条【不一样】：
                 *   POLISH 是「那句话没有信息量」，这里是「那个指令住在模板里」——
                 *   摘要是【服务端发起】的场景，客户端没有一句话可拼（作者点的是一个按钮，
                 *   按钮上没有文案）。★ 若设成 REQUIRED，就得由服务端现编一句
                 *   「把下面这些章节压缩成一段梗概」塞进去 —— 而那正是模板里已经写着的那句话，
                 *   模型会收到两遍同样的指令。
                 */
                AiVarRequirement.NOT_APPLICABLE,// request
                /*
                 * ★★ 这一格「必填」的后果是全表最重的：{{selected}} 是要压缩的正文本身，
                 *   缺了它模型不会报错，它会【自己编一段剧情梗概】——
                 *   而摘要与大纲/正文不同，它【没有「采纳」这一步】，编出来的东西直接落库，
                 *   再被下一章的 {{summary}} 当真前情喂回去。整条滚动链条从此建立在假话上。
                 *   所以配额必须在这之后才扣，失败也不许写行（见 AiSummaryServiceImpl）。
                 */
                AiVarRequirement.REQUIRED,      // selected —— 本次范围的章节正文
                AiVarRequirement.OPTIONAL,      // novelTitle —— 帮模型稳住人名/地名，取不到只是少一行
                /*
                 * ★ outline 是 NOT_APPLICABLE：大纲是「还没写的」，摘要是「已经写完的」，
                 *   两者在这条链上没有交集。这一格是【声明式】的 ——
                 *   它约束的是「摘要模板不许写 {{outline}}」，而不是「运行时可能缺」。
                 */
                AiVarRequirement.NOT_APPLICABLE,// outline
                /*
                 * ★★ {{summary}} 在本场景是【可选】而不是不适用，这一格就是整个场景存在的理由：
                 *   上一次压缩的产出作为「更早的剧情」喂给这一次，形成滚动链条。
                 *   ★ 首次压缩时它没有值 → 整行移除（判断 2.2），
                 *   模型看到的是「这是第一次压缩」，而不是「前情摘要：（空）」。
                 *   ★★ SUMMARY 是【唯一一个既产出又消费同一个占位符】的场景 ——
                 *   这正是「同一张表足以表达滚动摘要、不需要新机制」的证据。
                 */
                AiVarRequirement.OPTIONAL       // summary —— 上一次的产出
        ));

        return Collections.unmodifiableMap(table);
    }

    /** 按固定顺序把五个占位符的档位写成一格 —— 顺序与 {@link AiPlaceholder} 的声明顺序一致 */
    private static Map<AiPlaceholder, AiVarRequirement> scene(AiVarRequirement request,
                                                              AiVarRequirement selected,
                                                              AiVarRequirement novelTitle,
                                                              AiVarRequirement outline,
                                                              AiVarRequirement summary) {
        Map<AiPlaceholder, AiVarRequirement> map = new EnumMap<>(AiPlaceholder.class);
        map.put(AiPlaceholder.REQUEST, request);
        map.put(AiPlaceholder.SELECTED, selected);
        map.put(AiPlaceholder.NOVEL_TITLE, novelTitle);
        map.put(AiPlaceholder.OUTLINE, outline);
        map.put(AiPlaceholder.SUMMARY, summary);
        return map;
    }

    /**
     * 查一个占位符在某个场景里的档位。
     *
     * <p>★ 查不到时返回 {@link AiVarRequirement#NOT_APPLICABLE} 而不是抛异常：
     * 表是按场景逐个写全的，而 {@link AiPlaceholder} 将来可能新增第六个 ——
     * 那时<b>每个场景都会缺这一格</b>，而「新加的变量默认不适用于所有场景」
     * 是唯一安全的方向（反过来会让一个没人赋值的新变量在所有场景里变成必填，
     * 于是四个生成端点<b>同时</b>开始返 400）。
     */
    public static AiVarRequirement requirementOf(AiScene scene, AiPlaceholder placeholder) {
        Map<AiPlaceholder, AiVarRequirement> row = TABLE.get(scene);
        if (row == null) {
            return AiVarRequirement.NOT_APPLICABLE;
        }
        return row.getOrDefault(placeholder, AiVarRequirement.NOT_APPLICABLE);
    }

    /**
     * 某个场景下【允许出现在模板里】的占位符，按声明顺序。
     *
     * <p>★ 管理端的模板编辑器用它渲染「本场景可用变量」的提示与校验。
     * 用 {@link LinkedHashMap} 保序，是为了让提示里的顺序稳定 ——
     * 顺序每次都变的话，管理员会以为变量集变了。
     */
    public static Map<AiPlaceholder, AiVarRequirement> availableVars(AiScene scene) {
        Map<AiPlaceholder, AiVarRequirement> available = new LinkedHashMap<>();
        for (AiPlaceholder p : AiPlaceholder.values()) {
            AiVarRequirement r = requirementOf(scene, p);
            if (r.allowedInTemplate()) {
                available.put(p, r);
            }
        }
        return available;
    }
}
