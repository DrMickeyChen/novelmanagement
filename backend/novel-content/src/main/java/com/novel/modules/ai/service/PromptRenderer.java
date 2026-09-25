package com.novel.modules.ai.service;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.enums.AiPlaceholder;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.enums.AiVarRequirement;
import com.novel.modules.ai.vo.RenderedPromptVO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提示词渲染 —— <b>纯函数：同样是（模板 + 变量值），永远得到同一段文本</b>。
 *
 * <p>没有 Spring 依赖、不碰数据库、不调模型、不扣配额。★ 这不是洁癖：
 * 判断 2.2 的三档失败语义<b>全部发生在这里</b>，它们必须是<b>可以被单独验证</b>的 ——
 * 混进一个需要真数据库的类里，验证成本就从「调一次预览接口」变成「先把环境搭起来」。
 *
 * <h3>★★★ 三档失败语义（判断 2.2）</h3>
 *
 * <table border="1">
 *   <tr><th>情形</th><th>处置</th><th>为什么是它</th></tr>
 *   <tr><td>模板里有<b>不认识的</b>占位符 {@code {{foo}}}</td>
 *       <td>抛 {@link IllegalStateException} → <b>500</b></td>
 *       <td>★★ 模板已经存进库了还撞上未知占位符，只可能是<b>有人绕过了管理端改库</b>。
 *           保存时那种情况返 400（告诉管理员写错了），<b>而这里必须 500</b> ——
 *           故意不给好看的业务码：5001+ 返 HTTP 200 会<b>在监控上隐身</b></td></tr>
 *   <tr><td>模板里有本场景<b>不适用</b>的占位符（如审核模板写 {@code {{outline}}}）</td>
 *       <td>★ 按「无值」处理：整行移除</td>
 *       <td>保存时该拦（返 400），但<b>运行时不能崩</b> —— 否则哪天收窄一个场景的变量，
 *           库里已有的模板会突然 500。★ 它和一个「可选但这次没值」的变量
 *           在产出提示词的那一刻是<b>同一件事</b></td></tr>
 *   <tr><td><b>必填</b>占位符无值</td>
 *       <td>抛 {@link BusinessException}(400)，并<b>指名道姓</b>说缺哪个</td>
 *       <td>它是参与生成的输入。★ 给空串的后果是模型<b>自己编一段</b>，
 *           前端把它显示成「结果」让作者采纳，<b>而额度已经花掉了</b></td></tr>
 *   <tr><td><b>可选</b>占位符无值</td>
 *       <td>★ <b>该行整行移除</b>（含紧邻的纯标签行）+ INFO 日志</td>
 *       <td>铁律 10：<b>降级 = 让字段【消失】，不是让它变空</b>。
 *           模型收到的是一份语义完整的提示词，而不是一堆空标签</td></tr>
 * </table>
 *
 * <h3>★★★ 「整行移除」的判定规则（模板作者必须能预测它）</h3>
 *
 * <p>对一个无值的变量，<b>先把它的 token 从那一行里删掉，再看剩下什么</b>：
 * <pre>
 *   剩下的是空白                → 整行移除
 *   剩下的是一个【纯标签】       → 整行移除（纯标签 = 去掉首尾空白后以冒号结尾）
 *   还剩下别的东西              → 保留这一行，只是那个 token 变空
 * </pre>
 * 再补一条：<b>这一行被移除后，回头把它【前面】的空白行与纯标签行也一起吃掉</b>
 * （一直往前吃，直到碰到有内容的行）—— 这是为了照顾种子模板的分节写法：
 * <pre>
 *   作品名：{{novelTitle}}   ← 整行移除
 *   {{request}}             ← 保留
 *                           ← 空行，保留（它是下一节的分隔符）
 *   前情摘要：              ← 纯标签，跟着走
 *   {{summary}}             ← 整行移除
 *                           ← 空行，也被带走（否则和上一节之间会留下连续三个空行）
 *   当前章节大纲：
 *   {{outline}}
 * </pre>
 * ★ <b>级联只吃空白行和纯标签行</b>，含变量的行永远安全 ——
 * 把一个「看着像坏了、其实是对的」的结果交出去，代价是以后没人再相信这个预览器。
 *
 * <p>★★ 所以模板作者有两种写法，两种都安全：
 * <pre>
 *   前情摘要：{{summary}}     ← 推荐（标签和变量同行，规则更直观）
 *   前情摘要：
 *   {{summary}}              ← 也可以（靠上面那条「上一行是纯标签」规则兜住）
 * </pre>
 * ★ <b>但【不要】给变量加装饰</b>：{@code 作品名：《{{novelTitle}}》} 是错的 ——
 * 变量无值时它剩下 {@code 作品名：《》}，<b>既不是空白也不是纯标签</b>，
 * 于是这一行被保留，模型会收到一句「作品名：《》」。
 * ★★ 这正是本系统最反对的形状：<b>一句看起来正常的假话</b>。
 * （E1 写这一版时，种子模板里 {@code 作品名：《{{novelTitle}}》} 就是这么写的，
 * 已订正为 {@code 作品名：{{novelTitle}}}。判据在 {@link #blankOrLabel} 上。）
 *
 * <h3>★★ 为什么【没有】「渲染后不该再有 {{...}}」这条兜底断言</h3>
 *
 * <p>看着该加，其实加了也<b>永远不会失败</b>，那就是铁律 8 说的假护栏：
 * 模板里的 token 只可能来自 {@link AiPlaceholder}（不认识的在前面已经抛了），
 * 而每一个已知 token <b>要么有值（被替换）、要么无值（被移除或清空）</b> ——
 * 两种都已穷尽，不存在第三种。★ <b>一条不可能失败的护栏比没有护栏更糟</b>，
 * 因为它会让人以为这里已经被保护住了，从而不再去想真正会漏的那几条路径。
 * 真正能失败的是上面那张表里的<b>第一行和第三行</b>，两条都配了阳性对照。
 *
 * @author MickeyChen
 */
@Slf4j
public final class PromptRenderer {

    /**
     * 渲染结果的总长度上限（字符）。
     *
     * <p>★★ 它管的是<b>「这次调用最多能烧多少钱」</b>，不是「模型装不下」——
     * 现代模型的上下文远大于此。{@code @Size} 只管得住<b>客户端发来的两个字段</b>
     * （{@code prompt} 2000 + {@code context} 8000 = 10000），
     * 但渲染结果里还会<b>多出三段服务端查出来的内容</b>
     * （作品名、大纲节点、前情摘要），它们没有任何 {@code @Size} 管着
     * （前情摘要是一条 {@code TEXT} 列的摘要，长度由历史决定）。
     *
     * <p>★ 20000 是这样定的：客户端的 10000 之外，留给那三段约 10000 的余量 ——
     * <b>正常使用绝对碰不到</b>，碰到了就说明有人在拿一整本书当上下文。
     * <p>★★ 超限一律<b>失败</b>，绝不截断：截断正文中段会让模型接着一段
     * 「没头没尾」的文字续写，<b>产出看起来正常但接不上</b>。
     */
    public static final int MAX_RENDERED_LENGTH = 20000;

    private PromptRenderer() {
    }

    /**
     * 渲染。
     *
     * @param scene        场景，决定 {@code varValues} 里哪些缺失是致命的
     * @param systemPrompt 模板的 system 部分（可以没有占位符）
     * @param userTemplate 模板的 user 部分
     * @param varValues    变量名 → 值。<b>无值</b>用 {@code null} 或空串表示，
     *                     ★ 两种都要当作「没有」—— 只判 {@code null} 会让一个空串
     *                     从「整行移除」漏进「替换成空」，两种写法的结果不同，而管理员看不出区别
     * @throws IllegalStateException 模板里有不认识的占位符（只能来自绕过管理端改库）
     * @throws BusinessException     必填变量无值（{@code PARAM_ERROR} → HTTP 400），
     *                               或渲染结果超长（同样的码：都是「你给的输入不合适」）
     */
    public static RenderedPromptVO render(AiScene scene,
                                          String systemPrompt,
                                          String userTemplate,
                                          Map<AiPlaceholder, String> varValues) {
        assertNoUnknownToken(systemPrompt);
        assertNoUnknownToken(userTemplate);

        /*
         * ★ 先算「哪些变量没有值」，再动文本。
         *   顺序反过来的话，「必填缺失」就要在改完文本之后才发现 ——
         *   那时已经没必要了，而且日志里会留下一次假的渲染记录。
         *
         * ★★ 判据是【两个条件的与】：有值 AND 本场景允许它出现。
         *   第二个条件是必需的，不是防御性代码 —— 少了它，一个「本场景不适用」
         *   的变量只要调用方给了值就会被填进去。而生成路径永远不给它值，
         *   于是【同样一份模板会渲染出两份不同的提示词】：
         *   预览（管理端传了值）和生成（服务端不传）看到的不是一回事。
         *   ★ 而预览的全部价值就是「在作者按按钮之前，看见作者会遇到什么」。
         */
        Set<AiPlaceholder> absent = EnumSet.noneOf(AiPlaceholder.class);
        Set<AiPlaceholder> present = EnumSet.noneOf(AiPlaceholder.class);
        for (AiPlaceholder p : AiPlaceholder.values()) {
            if (hasValue(varValues.get(p)) && AiVarResolver.requirementOf(scene, p).allowedInTemplate()) {
                present.add(p);
            } else {
                absent.add(p);
            }
        }

        List<String> missingRequired = new ArrayList<>();
        for (AiPlaceholder p : absent) {
            /*
             * ★★ 用【本场景的档位】判断，而不是用「它在不在 absent 里」：
             *   NOT_APPLICABLE 与 OPTIONAL 都会落进 absent，但只有 REQUIRED 会拦下这次调用。
             */
            if (AiVarResolver.requirementOf(scene, p).blocksRendering()) {
                missingRequired.add(p.getLabel() + "（" + p.token() + "）");
            }
        }
        if (!missingRequired.isEmpty()) {
            /*
             * ★ 报错必须【指名道姓】。只说「缺少必填参数」的话，
             *   作者看着满屏正文里那句「润色下面这段文字」，不知道要去选中一段 ——
             *   而这个失败【作者自己能修好】，前提是我们告诉他要修什么。
             */
            String message = "这次生成缺少必要的内容：" + String.join("、", missingRequired)
                    + "。请补齐后重试。";
            log.info("提示词渲染中止：必填变量缺值 | scene={} | 缺={}", scene.getCode(), missingRequired);
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        }

        /*
         * ★ absent 里【去掉】本场景不适用的那些，剩下的才走「整行移除」的日志与返回。
         *   理由：NOT_APPLICABLE 是「这个场景压根没有这个变量」，
         *   把它报进 removedVars 会让管理端显示「删除了 {{outline}}」——
         *   而实际上这个场景的模板里本来就不该有它。两者混在一起，
         *   管理员会以为「模板里写了但没值」，从而去改一个没写的东西。
         */
        Set<AiPlaceholder> optionalAbsent = EnumSet.noneOf(AiPlaceholder.class);
        for (AiPlaceholder p : absent) {
            if (AiVarResolver.requirementOf(scene, p) == AiVarRequirement.OPTIONAL) {
                optionalAbsent.add(p);
            }
        }

        String renderedSystem = replaceAll(systemPrompt, varValues, absent);
        String renderedUser = replaceAll(userTemplate, varValues, absent);

        int total = length(renderedSystem) + length(renderedUser);
        if (total > MAX_RENDERED_LENGTH) {
            /*
             * ★ 刻意【不截断】。截断是这个项目里反复拒绝的形状：
             *   它产出的东西【看起来正常】（一段通顺的文字），
             *   而错在「接着一段没头没尾的文字续写」——
             *   没有异常、没有日志、作者也不会觉得哪里不对。
             */
            log.warn("提示词渲染超长，已拒绝 | scene={} | 长度={} | 上限={}",
                    scene.getCode(), total, MAX_RENDERED_LENGTH);
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "这次生成的内容太长了（" + total + " 字，上限 " + MAX_RENDERED_LENGTH
                            + " 字），请减少背景资料后重试。");
        }

        if (!optionalAbsent.isEmpty()) {
            log.info("提示词渲染：可选变量无值，整行移除 | scene={} | 移除={}", scene.getCode(), optionalAbsent);
        }

        RenderedPromptVO vo = new RenderedPromptVO();
        vo.setSystemPrompt(renderedSystem);
        vo.setUserMessage(renderedUser);
        vo.setUsedVars(RenderedPromptVO.namesOf(intersect(present, scene)));
        vo.setRemovedVars(RenderedPromptVO.namesOf(optionalAbsent));
        vo.setTotalLength(total);
        vo.setMaxLength(MAX_RENDERED_LENGTH);

        Map<String, AiVarRequirement> available = new LinkedHashMap<>();
        AiVarResolver.availableVars(scene).forEach((p, r) -> available.put(p.getName(), r));
        vo.setAvailableVars(available);

        return vo;
    }

    /**
     * 保存模板时的校验 —— 与管理端的新增 / 修改接口共用。
     *
     * <p>★★★ <b>未知占位符和「本场景不适用」都在这里拒绝，而不是等到渲染时</b> ——
     * 半径差三个数量级：写时拒绝 =「1 个管理员的下一次点击」，
     * 读时拒绝 =「全体作者 × 该场景」，而且故障出现在作者点按钮那一刻，
     * <b>错误指向的是产品功能，不是模板</b>。
     *
     * <p>★ 和 {@link #render} 的第一行是同两个判断、<b>不同的反应</b>（400 vs 500）——
     * 这不是重复，是「谁有能力修好它」不同：保存时是管理员在打字，渲染时是有人改了库。
     * 所以两个方法都保留，<b>不要为了去重把 save 时的校验删掉、指望渲染时兜住</b>。
     *
     * @throws BusinessException 400，附带具体是哪个占位符不合法
     */
    public static void validateTemplate(AiScene scene, String systemPrompt, String userTemplate) {
        List<String> unknown = collectUnknown(systemPrompt, userTemplate);
        if (!unknown.isEmpty()) {
            /*
             * ★ 报错要带上【本场景可用的变量】，否则管理员只知道「写错了」，
             *   还得自己去找文档。这一句话就能让他直接改对。
             */
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "模板里有系统不认识的占位符：" + String.join("、", wrap(unknown))
                            + "。本场景可用的变量是：" + describeAvailable(scene));
        }

        List<String> notApplicable = new ArrayList<>();
        for (String name : AiPlaceholder.extractNames(systemPrompt)) {
            collectNotApplicable(scene, name, notApplicable);
        }
        for (String name : AiPlaceholder.extractNames(userTemplate)) {
            collectNotApplicable(scene, name, notApplicable);
        }
        if (!notApplicable.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "模板里用了本场景没有的变量：" + String.join("、", wrap(notApplicable))
                            + "。本场景可用的变量是：" + describeAvailable(scene));
        }
    }

    /* ==================== 内部实现 ==================== */

    private static void collectNotApplicable(AiScene scene, String name, List<String> sink) {
        AiPlaceholder p = AiPlaceholder.fromToken(name);
        if (p != null && !AiVarResolver.requirementOf(scene, p).allowedInTemplate()
                && !sink.contains(p.getName())) {
            sink.add(p.getName());
        }
    }

    /** 把模板里所有【不认识】的占位符名字收集起来（去重、保序） */
    private static List<String> collectUnknown(String... templates) {
        List<String> unknown = new ArrayList<>();
        for (String t : templates) {
            for (String name : AiPlaceholder.extractNames(t)) {
                if (AiPlaceholder.fromToken(name) == null && !unknown.contains(name)) {
                    unknown.add(name);
                }
            }
        }
        return unknown;
    }

    /**
     * ★ 不认识的占位符 → 抛 500，理由见类注释第一行表格。
     * 用 {@code IllegalStateException} 而不是业务异常：它必须<b>在监控上响</b>。
     */
    private static void assertNoUnknownToken(String template) {
        List<String> unknown = collectUnknown(template);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "提示词模板里有未知占位符（只可能来自绕过管理端直接改库）：" + wrap(unknown)
                            + "。请到管理端「AI 配置 - 提示词模板」里重新保存一次该模板。");
        }
    }

    /**
     * 替换所有变量，并按规则移除无值的行。
     *
     * <p>★ {@code absent} 里<b>不区分</b> OPTIONAL 与 NOT_APPLICABLE —— 两者在这里
     * 收敛到同一个分支（理由见 {@link AiVarRequirement}）。区分它们只发生在
     * <b>保存校验</b>（400）和<b>返回给前端的 removedVars</b>（只报 OPTIONAL）两处。
     */
    private static String replaceAll(String template,
                                     Map<AiPlaceholder, String> values,
                                     Set<AiPlaceholder> absent) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }

        // ① 按行处理「整行移除」
        String[] lines = template.split("\n", -1);
        boolean[] drop = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) {
            String rest = lines[i];
            boolean touched = false;
            for (AiPlaceholder p : absent) {
                if (rest.contains(p.token())) {
                    touched = true;
                    rest = rest.replace(p.token(), "");
                }
            }
            if (!touched || !blankOrLabel(rest)) {
                continue;
            }
            drop[i] = true;
            /*
             * ★★ 往前走，把这条被移除的内容【前面】的标签行和空白行一并带走。
             *
             *   种子模板写的是「标签 / 变量 / 空行 / 标签 / 变量」这种分节格式：
             *       前情摘要：
             *       {{summary}}
             *       ← 这个空行是【下一节】的分隔符，前情摘要没了它就是孤立的
             *       当前章节大纲：
             *       {{outline}}
             *   ★ 只删「标签 + 变量」两行的话，两个被删的节会在原地留下
             *     【连续三个空行】—— 内容是对的，但管理员一预览就会以为渲染坏了。
             *     把一个「看着像坏了、其实是对的」的结果交出去，
             *     代价是以后没人再相信这个预览器。
             *
             *   ★ 级联的范围是硬的：只会吃掉【空白行】和【纯标签行】。
             *     含变量的行即使变量这次没值，也不会被误删（它在上面已经算过一次了）。
             */
            int j = i - 1;
            while (j >= 0 && !drop[j] && blankOrLabel(lines[j])) {
                drop[j] = true;
                j--;
            }
        }

        StringBuilder sb = new StringBuilder(template.length());
        for (int i = 0; i < lines.length; i++) {
            if (drop[i]) {
                continue;
            }
            /*
             * ★ ② 没被整行移除的行，仍要把【无值变量】的 token 换成空串。
             *   走到这里说明那一行还剩下别的内容（比如 `{{novelTitle}} {{summary}}`
             *   里只有 summary 没值）—— 留着 token 原文发给模型是最坏的结果：
             *   模型看到的是「{{summary}}」这 11 个字符，而界面上一切正常。
             */
            String line = lines[i];
            for (AiPlaceholder p : absent) {
                line = line.replace(p.token(), "");
            }
            // ③ 有值的变量替换成值
            for (AiPlaceholder p : AiPlaceholder.values()) {
                String v = values.get(p);
                if (hasValue(v)) {
                    line = line.replace(p.token(), v);
                }
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** 本场景【允许出现】且有值的占位符 —— 给前端显示「本次用到了哪些」 */
    private static Set<AiPlaceholder> intersect(Set<AiPlaceholder> present, AiScene scene) {
        Set<AiPlaceholder> result = EnumSet.noneOf(AiPlaceholder.class);
        for (AiPlaceholder p : present) {
            if (AiVarResolver.requirementOf(scene, p).allowedInTemplate()) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * ★★★ 本类最需要小心的一行：<b>「无值」= null 或空串，两者都算</b>。
     *
     * <p>只判 {@code null} 的话，一个<b>空串</b>会从「整行移除」漏进「替换成空」——
     * 两者在界面上<b>看不出区别</b>（那一行都不显示值），
     * 但一个留下「前情摘要：」这个空标签、另一个把那行删掉，
     * 而模型看到的是<b>两份不同的提示词</b>。这种「两种写法结果不同、而人看不出来」
     * 正是本项目一直在防的形状。
     *
     * <p>★ 顺带把纯空白也当作无值：{@code context} 可能是 {@code "   "}。
     */
    private static boolean hasValue(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * 去掉首尾空白后，剩下的是空白或一个【纯标签】。
     *
     * <p>纯标签 = 以冒号结尾（{@code ：} 或 {@code :}）且后面什么都没有。
     * ★ 判据就是这一条，模板作者能自己预测：
     * <pre>
     *   "作品名："          → 纯标签，移除        ✓
     *   "作品名：{{x}}"     → 变量删掉后是 "作品名：" → 移除   ✓（推荐的写法）
     *   "作品名：《{{x}}》" → 变量删掉后是 "作品名：《》" → 【保留】 ✗ 假话，别这么写
     *   "{{x}}"            → 变量删掉后是 ""      → 移除       ✓
     * </pre>
     */
    private static boolean blankOrLabel(String rest) {
        String t = rest.trim();
        return t.isEmpty() || isPureLabel(t);
    }

    private static boolean isPureLabel(String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        // 以冒号结尾 —— 冒号之后没有别的内容，才算「纯标签」
        return t.endsWith("：") || t.endsWith(":");
    }

    private static List<String> wrap(List<String> names) {
        List<String> wrapped = new ArrayList<>(names.size());
        for (String n : names) {
            wrapped.add("{{" + n + "}}");
        }
        return wrapped;
    }

    /** 把「本场景有哪些变量、哪些必填」写成一句人话，用在保存校验的报错里 */
    private static String describeAvailable(AiScene scene) {
        List<String> parts = new ArrayList<>();
        AiVarResolver.availableVars(scene).forEach((p, r) ->
                parts.add(p.token() + (r == AiVarRequirement.REQUIRED ? "(必填)" : "")));
        return String.join("、", parts);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
