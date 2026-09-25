package com.novel.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.entity.AiSummary;
import com.novel.modules.ai.enums.AiPlaceholder;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.mapper.AiSummaryMapper;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.entity.OutlineNode;
import com.novel.modules.content.mapper.OutlineNodeMapper;
import com.novel.modules.content.service.NovelAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把「请求里的三个 id」查成「模板要的三段内容」（E1）。
 *
 * <h3>★★★ 这个类必须在【请求线程】上跑，理由是安全，不是性能</h3>
 *
 * <p>它要读 {@code novel} / {@code novel_outline} / {@code ai_summary} ——
 * 而这三张表都属于<b>别人也可能拥有</b>的东西。所以每一步取数之前都要过
 * {@link NovelAccessGuard}（作者只能取自己作品的节点与章节上下文）。
 *
 * <p>★ 正因为它要过权限校验，它就<b>不能</b>被搬到 {@code ai-stream-N} 线程上去：
 * 那里的 {@code UserContext} 是空的（要靠 {@code AiController} 手工搬运），
 * 而一个「忘了搬身份 → 权限判成匿名 → 恰好全部放行」的写法
 * <b>不会有任何报错</b>。放在请求线程上，这件事就构造不出来。
 *
 * <h3>★★ 第二个职责：跨库拼接时不许把两本书混进同一份提示词</h3>
 *
 * <p>请求里的 {@code novelId} 与 {@code nodeId} / {@code chapterId} 是<b>三个独立字段</b>，
 * 客户端完全可以发来「A 书的 id + B 书的节点」。真放进提示词的话，
 * 模型会读到 A 书的名字配 B 书的剧情 —— <b>产出看着正常、但接错了书</b>，
 * 而两边都在自己权限内，权限校验一个字都不会报。
 * 所以这里显式核对，不一致就 400（见 {@link #requireSameNovel}）。
 *
 * <h3>★★ 只查本场景【用得上】的变量</h3>
 *
 * <p>判定走 {@link AiVarResolver}（唯一的场景×变量表），与渲染侧同一份来源。
 * 不去查用不上的东西，不只是省一次查询 —— 它同时避免了
 * 「为一个本场景不适用的变量判权限、于是失败」这种<b>看不懂的 403</b>：
 * 审核（AUDIT）场景里 {@code {{novelTitle}}} 是 NOT_APPLICABLE，
 * 而那本书是不是作者的，与审核一点关系都没有。
 *
 * <p>★ 最终「拿不到」的形态统一是 <b>{@code null} 或空串</b>，
 * 不是编一个「（无）」之类的兜底文案 —— 判断 2.2 的「可选无值 → 整行移除」
 * 就靠这个约定，见 {@code PromptRenderer#render} 对 {@code varValues} 的说明。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiContextAssembler {

    /**
     * ★ 这是 {@code modules/ai} 里<b>第二处</b> import {@code modules/content} 的地方
     * （第一处是 {@code AiUsageServiceImpl} 的 {@code ContentUserLookup}）。
     *
     * <p>它的合法性论证与那一处完全相同：<b>同进程内的模块引用，不是服务边界</b>，
     * 且方向单向（{@code content} 不 import {@code ai}）。铁律 1 管的是进程之间。
     * ★ 而这里比那一处更强的一条理由是：{@code NovelAccessGuard} 就是
     * 「作者只能碰自己的东西」这条规则的<b>唯一落点</b>，
     * 绕过它自己写一个 {@code novelMapper.selectById} 去判 authorId，
     * 等于把同一条规则实现两遍 —— <b>两遍迟早会不一样，而这一条的后果是越权</b>。
     */
    private final NovelAccessGuard accessGuard;

    private final OutlineNodeMapper outlineNodeMapper;
    private final AiSummaryMapper summaryMapper;

    /**
     * 组装变量表。
     *
     * @return 变量 → 值。★ 无值的变量<b>也要在表里</b>（值为 {@code null}），
     *         或者干脆不出现 —— 两种 {@code PromptRenderer} 都认，
     *         它把「表里没有」和「值是 null」都读成「没有值」。
     * @throws BusinessException 权限（{@code NovelAccessGuard} 的那几个码）或
     *                           {@code PARAM_ERROR(400)}（id 之间自相矛盾）
     */
    public Map<AiPlaceholder, String> assemble(AiScene scene, AiRequestDTO dto) {
        Map<AiPlaceholder, String> values = new EnumMap<>(AiPlaceholder.class);

        /*
         * ★ 作品级的两格（novelTitle / summary）共用同一次取数：
         *   一次 requireOwned 既完成了归属校验，也拿到了作品名。
         * ★ 而「没给 novelId」= 这两格都没有值，【不是】错误 ——
         *   它们都是 OPTIONAL（见 AiVarResolver 的表），
         *   少了就整行移除，AI 照样能跑（就是 P4 的行为）。
         */
        Novel novel = null;
        if (dto.getNovelId() != null
                && (wanted(scene, AiPlaceholder.NOVEL_TITLE) || wanted(scene, AiPlaceholder.SUMMARY))) {
            novel = accessGuard.requireOwned(dto.getNovelId());
        }

        OutlineNode node = wanted(scene, AiPlaceholder.OUTLINE) ? resolveOutlineNode(dto) : null;

        for (AiPlaceholder p : AiPlaceholder.values()) {
            if (!wanted(scene, p)) {
                // ★ 本场景不适用的变量【一个都不填】：填了 PromptRenderer 也会
                //   按「本场景不允许它出现」丢掉它（那个判断在 render 里），
                //   在这里跳过只是让「谁负责这件事」只有一个答案。
                continue;
            }
            switch (p) {
                case REQUEST -> values.put(p, dto.getPrompt());
                case SELECTED -> values.put(p, dto.getContext());
                case NOVEL_TITLE -> values.put(p, novel == null ? null : novel.getTitle());
                case OUTLINE -> values.put(p, node == null ? null : outlineText(node));
                case SUMMARY -> values.put(p, novel == null ? null : latestSummary(dto.getNovelId()));
            }
        }

        /*
         * ★★ 这一条日志是「为什么那几行消失了」的唯一答案，所以是 INFO 不是 DEBUG。
         *   三个 id 把「客户端给了什么」记下来，服务端补齐的那几格把
         *   「我们查到了什么」记下来 —— 两件事分开，才分得清
         *   「没有值（客户端没给）」和「取不到（给了但库里没有）」。
         */
        log.info("AI 上下文组装 | scene={} | novelId={} | nodeId={} | chapterId={} | 服务端补齐={}",
                scene.getCode(), dto.getNovelId(), dto.getNodeId(), dto.getChapterId(),
                serverFilled(values));

        return values;
    }

    /* ==================== 大纲节点 ==================== */

    /**
     * 取 {@code {{outline}}} 的来源：<b>优先 {@code nodeId}，其次 {@code chapterId}</b>。
     *
     * <p>★ 为什么是两个来源：四个生成场景手上拿的不是同一样东西 ——
     * outline / inspire 站在<b>树节点</b>上（有 {@code nodeId}），
     * write 站在<b>章节</b>上（有 {@code chapterId}）。
     * 而 {@code novel_outline.chapter_id} 这一列把两者连了起来（见它的实体注释）。
     *
     * <p>★ {@code chapterId} 那条查不到节点是<b>正常</b>的，不是错误：
     * 那一列可空，本项目支持「先写正文后补大纲」——
     * 于是「这一章还没有对应的大纲」是一个合法状态，
     * 它的表现就是 {@code {{outline}}} 那一行被移除（判断 2.2）。
     * ★ 把它做成一个错误的话，那批作者会连「续写」都用不了。
     */
    private OutlineNode resolveOutlineNode(AiRequestDTO dto) {
        if (dto.getNodeId() != null) {
            OutlineNode node = accessGuard.requireOwnedOutlineNode(dto.getNodeId());
            requireSameNovel(dto.getNovelId(), node.getNovelId(), "大纲节点", dto.getNodeId());
            return node;
        }
        if (dto.getChapterId() != null) {
            Chapter chapter = accessGuard.requireOwnedChapter(dto.getChapterId());
            requireSameNovel(dto.getNovelId(), chapter.getNovelId(), "章节", dto.getChapterId());
            return outlineNodeOfChapter(chapter.getId());
        }
        return null;
    }

    /**
     * 某一章挂着的那个大纲节点。
     *
     * <p>★ 一章理论上只有一个节点，但 {@code idx_chapter_id} <b>不是唯一索引</b> ——
     * 所以这里不用 {@code selectOne}（多行会抛 {@code TooManyResultsException} → 500），
     * 而是取 id 最小的那个并告警。
     * ★ 与 {@code AiConfigServiceImpl.currentRow()} 同一条：<b>把「有多行时用哪一行」
     * 变成一个可预期的答案</b>，而不是交给执行计划 —— 它决定了提示词里出现哪段大纲。
     *
     * <p>★ {@code .eq(...)} 走的是 MP 的 wrapper，{@code @TableLogic} 照常生效
     * （被删掉的节点不会冒出来）。这条只对手写 {@code @Select} 不成立。
     */
    private OutlineNode outlineNodeOfChapter(Long chapterId) {
        List<OutlineNode> nodes = outlineNodeMapper.selectList(
                Wrappers.<OutlineNode>lambdaQuery()
                        .eq(OutlineNode::getChapterId, chapterId)
                        .orderByAsc(OutlineNode::getId));
        if (nodes.isEmpty()) {
            return null;
        }
        if (nodes.size() > 1) {
            log.warn("章节挂了 {} 个大纲节点，本次取 id 最小的那个 | chapterId={} | 全部 id={}",
                    nodes.size(), chapterId, nodes.stream().map(OutlineNode::getId).toList());
        }
        return nodes.get(0);
    }

    /**
     * 节点 → 提示词里那一段文本：<b>标题一行 + 内容一块</b>。
     *
     * <p>★ 不加「以下是节点」这类前缀：模板自己已经写了标签行
     * （{@code 已有大纲：} / {@code 当前节点：}），再加一层就重复了 ——
     * 而且同一个占位符在 OUTLINE 和 INSPIRE 两个模板下面的标签<b>不是同一句话</b>，
     * 任何写死的前缀都只对其中一个通顺。
     *
     * <p>★★ 两样都空时返回空串 —— 空串会被 {@code PromptRenderer} 读成「没有值」，
     * 于是那一行连同它的标签行一起被移除（判断 2.2）。
     * <b>绝不返回「（空节点）」之类的占位文案</b>：那会把「这个节点没写内容」
     * 变成一句给模型看的话，模型会当真。
     */
    private static String outlineText(OutlineNode node) {
        List<String> lines = new ArrayList<>(2);
        if (hasText(node.getTitle())) {
            lines.add(node.getTitle().trim());
        }
        if (hasText(node.getContent())) {
            lines.add(node.getContent().trim());
        }
        return String.join("\n", lines);
    }

    /* ==================== 前情摘要 ==================== */

    /**
     * 取最新一条前情摘要的正文。
     *
     * <p>★ 查不到返回 {@code null}（而不是空串）：两种情况对 {@code PromptRenderer}
     * 是同一件事，但 {@code null} 更能表达「这里根本没有这一行数据」。
     *
     * <p>★★ 它和「取不到」的区别要留意：<b>查库失败会抛异常</b>（数据库连不上 → 500），
     * 而 <b>查得到、只是没有这一行</b> 才是 {@code null}。
     * 这两件事在界面上必须不同（M5 立下的判据），在这里分别是 500 和「那一行消失」。
     */
    private String latestSummary(Long novelId) {
        AiSummary latest = summaryMapper.selectLatestByNovelId(novelId);
        return latest == null ? null : latest.getSummary();
    }

    /* ==================== 交叉核对 ==================== */

    /**
     * 核对「请求里的 novelId」与「节点/章节真正所属的书」是不是同一本。
     *
     * <p>★★ 不核对的后果不是安全漏洞（两边都已经过归属校验了），而是
     * <b>一份静默错误的提示词</b>：A 书的作品名 + B 书的大纲。
     * 模型会照着一份自相矛盾的背景写下去，而产出<b>看起来完全正常</b> ——
     * 典型的「看起来正常的假结果」。
     *
     * <p>★ 用 {@code PARAM_ERROR(400)} 而不是某个业务码：这是<b>调用方自己发来的
     * 三个字段互相打架</b>，不是系统状态问题，而且调用方（前端）能改。
     * 400 落在 {@code ResultHttpStatus} 的语义区间里 → 真的返 HTTP 400。
     *
     * <p>★ 只在 {@code novelId} 给了的时候核对：没给就无从核对，
     * 那时归属仍由 {@code NovelAccessGuard} 单独保证（它自己会读节点/章节所属的书）。
     */
    private static void requireSameNovel(Long novelId, Long actualNovelId, String what, Long id) {
        if (novelId == null || Objects.equals(novelId, actualNovelId)) {
            return;
        }
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "请求里的 novelId 与" + what + "（id=" + id + "）对不上：它们不属于同一本书。");
    }

    /* ==================== 工具 ==================== */

    /** 本场景的模板里允许出现这个占位符吗（= 需不需要为它取数） */
    private static boolean wanted(AiScene scene, AiPlaceholder placeholder) {
        return AiVarResolver.requirementOf(scene, placeholder).allowedInTemplate();
    }

    /** 服务端补的那三格里有值的那些 —— 只用于日志 */
    private static List<String> serverFilled(Map<AiPlaceholder, String> values) {
        List<String> filled = new ArrayList<>(3);
        for (AiPlaceholder p : List.of(AiPlaceholder.NOVEL_TITLE, AiPlaceholder.OUTLINE, AiPlaceholder.SUMMARY)) {
            if (hasText(values.get(p))) {
                filled.add(p.getName());
            }
        }
        return filled;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
