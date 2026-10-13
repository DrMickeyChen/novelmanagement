package com.novel.modules.ai.enums;

import lombok.Getter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板里的五个占位符（E1）。
 *
 * <p><b>★★★ 这是占位符词汇表的【唯一真相来源】。</b>
 * 模板的渲染（{@code PromptRenderer}）、保存时的校验（管理端）、
 * 以及 {@code docs/sql/seed.sql} 里的五条种子模板，全部以它为准。
 * <b>想加第六个占位符，先改这里 —— 别在别的文件里直接写字符串字面量。</b>
 *
 * <h3>★★★ 判据：每个占位符【只有一个来源】</h3>
 *
 * <table border="1">
 *   <tr><th>占位符</th><th>唯一来源</th><th>为什么是它</th></tr>
 *   <tr><td>{@code {{request}}}</td><td>{@code AiRequestDTO.prompt}</td>
 *       <td>★ <b>节点身份只在这句话里</b>（「为「X」生成下一级大纲」）。没有它
 *           OUTLINE 模板拿不到该往哪个节点挂 —— 那是「把指令说成大纲」，
 *           对模型说的假话，正是 {@code AiRequestDTO} 注释拒绝的那件事</td></tr>
 *   <tr><td>{@code {{selected}}}</td><td>{@code AiRequestDTO.context}</td>
 *       <td>「要处理的东西」（正文末尾 / 选中段 / 待审正文）。
 *           ★ 这不是猜：{@code NodeWorkspace.vue} 的 {@code gatherAiRequest}
 *           把四类素材【全部】放在 {@code context} 里，逐行吻合</td></tr>
 *   <tr><td>{@code {{novelTitle}}}</td><td>{@code novelId} → {@code novel.title}</td>
 *       <td rowspan="3">★ 这三个是<b>服务端知道、而前端不发</b>的信息 ——
 *           让「模板管理」有真实增量。★★ 三张表（{@code novel} / {@code novel_outline} /
 *           {@code ai_summary}）<b>全在 content 自己的库里</b>，
 *           所以是<b>零跨服务、零新出站边</b>（铁律 1）</td></tr>
 *   <tr><td>{@code {{outline}}}</td>
 *       <td>{@code nodeId} → 节点标题+内容<br>（WRITE 场景走 {@code chapterId} → {@code novel_outline.chapter_id}）</td></tr>
 *   <tr><td>{@code {{summary}}}</td>
 *       <td>{@code novelId} → {@code ai_summary} 里 {@code end_chapter_no} 最大的一条</td></tr>
 * </table>
 *
 * <p>★ 判据不是「这几个名字好不好听」，而是
 * <b>「来源必须是请求契约上本来就存在的东西」</b>：
 * {@code {{request}}} / {@code {{selected}}} 是同两个字段的<b>语义化改名</b>，
 * 不是第二套体系 —— 所以 E1 <b>不新增任何输入字段</b>给作者填写。
 *
 * <h3>★★ 各场景的必填 / 可选不在这里，在 {@code AiVarResolver}</h3>
 *
 * <p>「某个占位符在这个场景里是不是必填」是<b>场景与占位符的叉乘</b>，
 * 不属于词汇表本身。这里只管「有哪些占位符、各自从哪来」。
 *
 * <h3>★★★ 校验与渲染必须共用同一份定义（否则是个静默的洞）</h3>
 *
 * <p>{@link #TOKEN_PATTERN} 与 {@link #fromToken} 被<b>保存时的校验</b>和
 * <b>渲染时的替换</b>同时使用。★ 如果两边各写一套正则，会出现
 * 「模板被接受、但渲染时不认识它」的组合 —— 结果是那个占位符
 * <b>原样字面量发给模型</b>（模型看到的是 {@code {{foo}}} 十几个字符），
 * 而<b>界面上一切正常</b>。这正是本项目记录在案的那种坏形状。
 *
 * @author MickeyChen
 */
@Getter
public enum AiPlaceholder {

    /**
     * 用户的指令。来源：{@code AiRequestDTO.prompt}（唯一来源）。
     *
     * <p>★ 它是一句<b>完整的祈使句</b>（「为「第一卷」生成下一级大纲」），
     * 所以模板里应当<b>独占一行、不加标签</b> ——
     * 任何标签都会把一句话变成字段值，读出来就别扭了：
     * 「当前章节：接着往下写「雨夜」」。
     */
    REQUEST("request", "用户的指令"),

    /**
     * 要处理的素材。来源：{@code AiRequestDTO.context}（唯一来源）。
     *
     * <p>★ 四个场景里它分别是：正文末尾 2000 字 / 用户选中的那段 / 节点剧情笔记 /
     * 待审正文。★ 在 WRITE 与 POLISH 里它是<b>必填</b>：
     * 没有正文，「续写」和「润色」就无从谈起 —— 给空串的话模型会<b>自己编一段</b>，
     * 前端把它显示成「润色结果」让作者采纳，<b>而额度已经花掉了</b>。
     */
    SELECTED("selected", "要处理的素材"),

    /** 作品名。来源：{@code novelId} → {@code novel.title}。 */
    NOVEL_TITLE("novelTitle", "作品名"),

    /**
     * 大纲节点。来源：{@code nodeId} → 该节点的标题与内容；
     * ★ WRITE 场景手上没有 {@code nodeId}，走 {@code chapterId} → {@code novel_outline.chapter_id}。
     *
     * <p>★ 那一列<b>可空</b>（「支持先写大纲后建正文」），所以查不到是<b>正常情况、不是错误</b>。
     */
    OUTLINE("outline", "大纲节点"),

    /** 前情摘要。来源：{@code novelId} → {@code ai_summary} 里 {@code end_chapter_no} 最大的一条。 */
    SUMMARY("summary", "前情摘要");

    /**
     * ★★ 占位符的匹配式 —— <b>校验与渲染共用这一份</b>，理由见类注释。
     *
     * <p>名字限定为纯字母：写成 {@code \w+} 会把 {@code {{novel_title}}} 这种
     * 「看着像但查不到」的写法放进去，然后在渲染时才炸。
     * <p>★ 捕获组 1 是名字（不含花括号）。
     */
    public static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([a-zA-Z]+)}}");

    /** 模板里写的名字（不含花括号），如 {@code novelTitle} */
    private final String name;

    /** 给人看的说明，出现在「未知占位符」的报错里 */
    private final String label;

    AiPlaceholder(String name, String label) {
        this.name = name;
        this.label = label;
    }

    /** 模板里真正写的那个 token，如 {@code {{novelTitle}}} */
    public String token() {
        return "{{" + name + "}}";
    }

    /**
     * 按名字查占位符，查不到返回 {@code null}。
     *
     * <p>★ <b>返回 {@code null} 而不是抛异常是刻意的</b>：调用方分两种，
     * 而它们对「不认识这个名字」的反应<b>必须不同</b> ——
     * <ul>
     *   <li><b>保存模板时</b>（管理端）：这是<b>用户输错了</b>，要返 400 并指出是哪个词；</li>
     *   <li><b>渲染时</b>：模板已经存进库了，还撞上未知占位符说明
     *       <b>有人绕过了管理端改库</b> —— 要抛 {@code IllegalStateException} 走 500，
     *       ★ <b>故意不给好看的业务码</b>：5001+ 返 HTTP 200 会<b>在监控上隐身</b>。</li>
     * </ul>
     * 把这两种反应塞进同一个方法里，就没有办法同时满足两边。
     */
    public static AiPlaceholder fromToken(String name) {
        for (AiPlaceholder p : values()) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 扫出模板里用到的全部占位符名字（含未知的），按出现顺序去重。
     *
     * <p>★ 返回名字而不是 {@link AiPlaceholder}，是因为<b>未知的那些也要能报出来</b> ——
     * 校验的目的就是告诉用户「你写的 {@code {{foo}}} 系统不认识」，
     * 如果这里只返回认识的那些，报错就只能说「模板里有非法占位符」，等于没说。
     *
     * <p>★ 用 {@link LinkedHashSet} 保序去重：报错时按模板里的书写顺序列出，
     * 管理员一眼能对上自己刚写的那一行。
     */
    public static Set<String> extractNames(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null || template.isEmpty()) {
            return names;
        }
        Matcher m = TOKEN_PATTERN.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
