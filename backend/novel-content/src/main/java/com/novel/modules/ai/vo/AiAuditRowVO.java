package com.novel.modules.ai.vo;

import com.novel.common.enums.AuditTargetType;
import com.novel.modules.ai.entity.AiAuditResult;
import com.novel.modules.ai.enums.AiRiskLevel;
import com.novel.modules.ai.service.AiAuditService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违禁审核队列的一行，服务 {@code GET /api/admin/ai/audit}（E1/S9）。
 *
 * <h3>★★★ 这一行的每一个字段都在回答「我能不能据此处置它」</h3>
 *
 * <p>队列是<b>待办</b>，不是报表。所以三件事必须同时在这一行上看得见，
 * 缺一条都会让人做出错误的处置：
 * <ol>
 *   <li><b>它是什么</b>（{@link #targetTypeDesc} / {@link #targetTitle} / {@link #novelTitle}）
 *       —— {@code audit_log} 那一页为这件事写过一整段论证：
 *       只给 {@code id} 的话，管理员看到「章节 #101307」完全不知道发生了什么。</li>
 *   <li><b>为什么进来</b>（{@link #riskLevelDesc} / {@link #hitWords} / {@link #aiComment} /
 *       {@link #aiStateDesc}）—— ★★ 尤其是最后那个：
 *       {@code ai_comment} 里可能存的是<b>一句固定的话</b>（「AI 复核未完成」），
 *       那是「模型没跑成」，不是「模型说没问题」。两者在纯文本里长得一样，
 *       而它们的处置完全不同（前者要靠词库 + 人工，后者可以放心排除）。</li>
 *   <li><b>判的是不是当前这一版</b>（{@link #createTime} 与 {@link #contentUpdatedAt} 并排）
 *       —— ★ 这是判断 3.2 里替代 {@code content_hash} 列的那两列：
 *       {@code create_time} 早于 {@code contentUpdatedAt} 就说明<b>内容改过、这次判定过期了</b>。
 *       本仓库没有 ALTER 惯例（R15），所以不加哈希列，改为把两个时间戳摆在一起让人自己看。</li>
 * </ol>
 *
 * <h3>★★ 两个字段刻意【不是】结构化数据</h3>
 *
 * <ul>
 *   <li><b>{@link #hitWords} 是一整段字符串，页面不要按分隔符拆开做标签。</b>
 *       它是 {@code AiAuditService#hitWordsOf} 拼出来的：<b>分隔符是全角「，」，
 *       而超限时追加的那句「（共N个词，此处列前M个）」里【也含】这个字符</b> ——
 *       按分隔符拆会把统计句拆成两半，页面显示成
 *       「（共30个词」+「此处列前20个）」。★ 这一列是给人读的，不是给程序读的。</li>
 *   <li><b>{@link #targetTitle} 对评论来说是【正文截断】，不是标题。</b>
 *       评论表没有标题列，正文开头三十个字就是它的名字（截断规则只在
 *       {@code InternalCommentQueryService} 里，本类不重做）。</li>
 * </ul>
 *
 * <h3>★★★ 「不知道」在这张 VO 上的四种表达，一个都不许被折叠</h3>
 *
 * <pre>
 *   targetTitle 为空        → 对象被逻辑删除了（★ 不是「查不到」，逻辑删是唯一成因）
 *   novelTitle  为空        → 同上，书被删了
 *   auditUserName 为空      → 跨服务取昵称失败（auth 挂了），★ 页面回落「用户 #7」
 *   contentUpdatedAt 为空   → 见 contentImmutable：评论正文不可修改，所以没有「最后更新时间」
 * </pre>
 * ★ 最后一条是本 VO 里唯一一个「为空不是因为出了问题」的字段，
 * 所以它<b>必须</b>配一个 {@link #contentImmutable} 来说明原因 ——
 * 否则页面只能把它渲染成「—」，而「—」在别的行上意味着「取不到」。
 * <b>同一个符号在同一个表格里表示两件事，是 M5 那条判据（消费方能不能诚实渲染）的反面。</b>
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "违禁审核队列的一行")
public class AiAuditRowVO {

    @Schema(description = "行 id（= ai_audit_result.id）")
    private Long id;

    /**
     * ★ 裸数字（1小说 2章节 3评论），<b>不是 {@code AuditTargetType} 枚举</b> ——
     * 这一处故意和 {@code AuditLogVO} 不一样。
     *
     * <p>理由：{@code audit_log.target_type} 的四个取值是应用自己写的，一定能转成枚举；
     * 而本表的这一列是<b>裸 TINYINT</b>（DDL 与实体都刻意如此，见 {@code AiAuditResult}），
     * 用 {@code AuditTargetType.fromCode} 转的话，<b>一行认不出来的脏数据会让整个列表 500</b> ——
     * 那是拿一条坏行惩罚另外 50 条好行（同 {@code AiRiskLevel.labelOf} 的论证）。
     * ★ 页面用 {@code targetType} 判跳转、用 {@code targetTypeDesc} 显示，
     * 认不出来的类型 {@code targetTypeDesc} 为空而裸数字照样显示。
     */
    @Schema(description = "对象类型：1小说 2章节 3评论", example = "2")
    private Integer targetType;

    /** ★ 认不出来的编号为 null（不抛异常），页面显示裸数字 */
    @Schema(description = "类型文案，★ 仅供显示", example = "章节")
    private String targetTypeDesc;

    @Schema(description = "对象 id", example = "101307")
    private Long targetId;

    /** 章节标题 / 评论正文截断。★ 对象被逻辑删除时为空，页面显示「章节 #101307（已删除）」 */
    @Schema(description = "对象标题。★ 已删除时为空")
    private String targetTitle;

    /** ★ 跳转用（章节审核页的 URL 里要它）：章节行来自 {@code chapter.novel_id}，评论行来自评论挂载的对象 */
    @Schema(description = "所属作品 id。★ 补不出来时为空，此时不能跳转")
    private Long novelId;

    @Schema(description = "所属作品名。★ 书被删时为空")
    private String novelTitle;

    @Schema(description = "风险等级：0无风险 1低 2中 3高", example = "2")
    private Integer riskLevel;

    /** ★ 由 {@code AiRiskLevel.labelOf} 给出（宽松查表）。页面不维护映射 */
    @Schema(description = "风险等级文案", example = "中")
    private String riskLevelDesc;

    /** ★ 见类注释：这是一整段给人读的字符串，页面不要按分隔符拆 */
    @Schema(description = "命中的关键词（★ 整段字符串，不要按分隔符拆）", example = "占位敏感词甲")
    private String hitWords;

    /**
     * 模型给出的理由。
     *
     * <p>★★ <b>它可能是两句固定文案之一</b>，见 {@link #aiStateDesc}：
     * {@code AiAuditService#AI_SKIPPED_COMMENT}（模型没跑成）与
     * {@code AiAuditService#AI_NO_REASON_COMMENT}（模型跑了但没给理由）。
     * <p>★ 原样返回，<b>不在服务端替换成别的措辞</b> —— 那句话是写进库里的判定的一部分，
     * 队列页显示的和日后查库看到的是同一句。
     */
    @Schema(description = "模型给出的理由。★ 可能是「模型未复核」的固定文案，见 aiState")
    private String aiComment;

    /**
     * 这一行的判定里，模型到底参与了多少。<b>取值只有四个，没有一个 {@code null}</b>：
     * <pre>
     *   NOT_CALLED  未调用模型 —— ai_comment 为空。唯一成因是关键词层没命中，
     *               于是「机器排除」了它（status=2，且 audit_user_id 为空），不花这一次钱。
     *   SKIPPED     模型没跑成 —— ai_comment 里是「AI 复核未完成」那句固定文案，
     *               risk_level 与 hit_words 来自【本地词库】。★ 这不是「AI 说没问题」。
     *   NO_REASON   模型跑了、判了，但没给理由 —— risk_level 取了模型判定与词库中的较高者。
     *   RAN         模型给了理由，ai_comment 是人话。
     * </pre>
     * ★★ <b>为什么不由页面去比对那两句文案</b>：那等于把两句文案变成接口契约 ——
     * 改一个字，页面的判断就<b>静默失效</b>（不再是「模型未复核」而是「模型说了句怪话」）。
     * 判据在服务端算一次，页面只渲染。
     * <p>★ <b>为什么 {@code NOT_CALLED} 要显式给一个 token，而不是让 {@code aiState} 为空</b>：
     * 空值在表格里与「这个字段没返回」长得一样，而页面得给这一格写一句话；
     * 让页面用 {@code aiComment 为空} 去推，就是把同一条判断抄成两份。
     * 四个状态各占一个 token，页面只做映射。
     */
    @Schema(description = "模型参与程度：NOT_CALLED 未调用 / SKIPPED 未跑成 / NO_REASON 跑了没理由 / RAN 有理由")
    private String aiState;

    /** 上面那个 token 的人话。★ 页面直接显示这一句 */
    @Schema(description = "模型参与程度（人话）", example = "模型未复核")
    private String aiStateDesc;

    /** 判定状态：0待确认 1已确认违规 2已排除 */
    @Schema(description = "状态：0待确认 1已确认违规 2已排除", example = "0")
    private Integer status;

    /**
     * 状态文案，<b>四档不是三档</b>：
     * <pre>
     *   0            → 待确认
     *   1            → 已确认违规
     *   2 + 没人处置  → ★ 机器排除
     *   2 + 有人处置  → 已排除
     * </pre>
     * ★★ 判据（与 {@code AiAuditResult} 类注释逐字一致）：
     * {@code status=2 且 audit_user_id IS NULL} = 机器排除的。
     * 把两者都显示成「已排除」，等于<b>替一个人认领了一个他没做过的动作</b> ——
     * 而这一页就是用来追责的。
     * <p>✗ <b>不能</b>改用「{@code ai_usage_log} 那种 {@code user_id=0} 哨兵」的写法：
     * 这一列<b>可空</b>，{@code NULL} 本就是准确表达。
     */
    @Schema(description = "状态文案（★ 含「机器排除」）", example = "待确认")
    private String statusDesc;

    /** 处置人 id。★ 待确认的行为空 */
    @Schema(description = "处置人 id")
    private Long auditUserId;

    /**
     * 处置人昵称。★ 可能为空 —— 它走 {@code ContentUserLookup#nicknamesOf}，
     * 那条接缝在 auth 挂掉时<b>降级成空 Map</b>（这是刻意设计的，见 {@code AuthUserClientFallbackFactory}），
     * 页面回落显示「用户 #7」。
     * ★★ 回落文案<b>不能</b>是「佚名」：那句话在 {@code NovelVO.authorName} 上成立，
     * 在这里是关于某个人的<b>具体断言</b>，而它没有被验证过。
     */
    @Schema(description = "处置人昵称。★ 取不到时不返回该字段，页面回落「用户 #id」")
    private String auditUserName;

    @Schema(description = "处置时间。★ 与 createTime 不是一回事")
    private LocalDateTime auditTime;

    /** AI 判定时间。★ 与 {@link #contentUpdatedAt} 并排看，才知道判定过期没有 */
    @Schema(description = "AI 判定时间")
    private LocalDateTime createTime;

    /**
     * 内容最后一次变更的时刻。
     *
     * <p>★★ <b>评论恒为 {@code null}，而且那不是「取不到」</b> ——
     * {@code novel_comment} 表<b>根本没有 {@code update_time} 列</b>，
     * 且评论正文<b>端到端不可修改</b>（interaction 里所有 UPDATE 只碰
     * {@code like_count} / {@code deleted} / {@code status,audit_*} 四类列，
     * 正文一次都没有出现在 SET 里）。所以对评论来说
     * {@code create_time} 就是版本戳，内容<b>不可能</b>与判定当时不同。
     * ★ 页面据此渲染成「评论不可修改」而不是「—」：前者是事实，后者会被读成故障。
     * （见 {@link #contentImmutable}。）
     */
    @Schema(description = "内容最后更新时间。★ 评论恒为空，见 contentImmutable")
    private LocalDateTime contentUpdatedAt;

    /**
     * true = 这个对象的正文<b>不可能被修改</b>，所以 {@link #contentUpdatedAt} 为空
     * 是结构性的，不是取不到。
     *
     * <p>★ 目前恒真的只有评论（依据见上面那个字段的注释）。
     * <p>★★ 判定按 {@code targetType} 给，<b>不按「这一行还在不在」给</b>：
     * 评论被删掉之后这个值仍然是 true —— 它说的是<b>那张表的结构</b>，
     * 与某一行是否存在无关。反过来写会让「评论删了」和「评论能改」表现出同一个样子。
     * <p>✗ 不给它加 {@code default-property-inclusion} 的例外：{@code false} 不是 {@code null}，
     * 全局的 {@code non_null} 本来就会把它序列化出去。
     */
    @Schema(description = "true = 正文不可修改（所以没有「最后更新时间」这一说）")
    private Boolean contentImmutable;

    /**
     * 装配一行。<b>只做转换，不查任何库</b> —— 标题 / 书名 / 昵称都由调用方
     * 批量取好、包在 {@code facts} 里传进来（纪律抄自 {@code AuditLogVO#of} 与
     * {@code AiUsageVO#of}：一旦这里能查库，一个分页列表就会变成 N 次查询，
     * 而且<b>在代码上看不出来</b>）。
     *
     * @param row          判定行
     * @param facts        类型相关的展示素材，见 {@link TargetFacts}
     * @param auditUserName 处置人昵称，<b>允许为 null</b>（跨服务降级）
     */
    public static AiAuditRowVO of(AiAuditResult row, TargetFacts facts, String auditUserName) {
        AiAuditRowVO vo = new AiAuditRowVO();
        vo.setId(row.getId());
        vo.setTargetType(row.getTargetType());
        vo.setTargetTypeDesc(targetTypeDesc(row.getTargetType()));
        vo.setTargetId(row.getTargetId());
        vo.setTargetTitle(facts.title());
        vo.setNovelId(facts.novelId());
        vo.setNovelTitle(facts.novelTitle());
        vo.setRiskLevel(row.getRiskLevel());
        vo.setRiskLevelDesc(AiRiskLevel.labelOf(row.getRiskLevel()));
        vo.setHitWords(row.getHitWords());
        vo.setAiComment(row.getAiComment());
        /*
         * ★ 状态先算，因为 statusDesc 要用到「有没有人处置过」——
         *   那个判别式是本 VO 里唯一一处「同一列 + 另一列 = 第四种状态」。
         */
        vo.setStatus(row.getStatus());
        vo.setStatusDesc(statusDesc(row.getStatus(), row.getAuditUserId()));
        vo.setAuditUserId(row.getAuditUserId());
        vo.setAuditUserName(auditUserName);
        vo.setAuditTime(row.getAuditTime());
        vo.setCreateTime(row.getCreateTime());
        vo.setContentUpdatedAt(facts.contentUpdatedAt());
        vo.setContentImmutable(facts.contentImmutable());

        vo.setAiState(aiState(row.getAiComment()));
        vo.setAiStateDesc(aiStateDesc(vo.getAiState()));
        return vo;
    }

    /**
     * 类型相关的展示素材（章节行与评论行的取值方式完全不同，见
     * {@code AiAuditQueueService} 的 {@code toVOList}）。
     *
     * <p>★ 做成一个 record 而不是给 {@code of} 塞五个参数：
     * 五个 {@code String}/{@code Long} 挨在一起，<b>调用处传错顺序编译器不会报错</b>
     * （书名和章节标题都是 String），症状是「标题那一格显示的是书名」。
     *
     * @param title          章节标题 / 评论正文截断；对象已删为 {@code null}
     * @param novelId        所属作品 id；补不出来为 {@code null}（页面据此禁用跳转）
     * @param novelTitle     所属作品名；补不出来为 {@code null}
     * @param contentUpdatedAt 内容最后更新时间；★ 评论恒为 {@code null}（见 VO 主注释）
     * @param contentImmutable {@code true} = 正文不可修改（评论）
     */
    public record TargetFacts(String title, Long novelId, String novelTitle,
                              LocalDateTime contentUpdatedAt, boolean contentImmutable) {
    }

    /**
     * ★ 宽松查表：认不出来的编号返回 {@code null}（页面显示裸数字），<b>不抛异常</b>。
     * 理由与 {@code AiRiskLevel.labelOf} 逐字相同 —— 一行脏数据不该让整个列表 500。
     */
    private static String targetTypeDesc(Integer targetType) {
        if (targetType == null) {
            return null;
        }
        return switch (targetType) {
            case 1 -> AuditTargetType.NOVEL.getDesc();
            case 2 -> AuditTargetType.CHAPTER.getDesc();
            case 3 -> AuditTargetType.COMMENT.getDesc();
            default -> null;
        };
    }

    /**
     * 状态文案。★★ 四档，不是三档 —— 理由见 {@link #statusDesc} 字段上的注释。
     *
     * <p>★ 认不出来的 {@code status} 返回 {@code null} 而不是「未知状态」：
     * 同 {@code AiUsageVO#sceneDesc} 的取值纪律 —— 不能编一个看着像正常取值的字符串。
     */
    private static String statusDesc(Integer status, Long auditUserId) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case AiAuditResult.STATUS_PENDING -> "待确认";
            case AiAuditResult.STATUS_CONFIRMED -> "已确认违规";
            case AiAuditResult.STATUS_EXCLUDED -> auditUserId == null ? "机器排除" : "已排除";
            default -> null;
        };
    }

    /**
     * 模型参与程度，见 {@link #aiState} 字段上的表。
     *
     * <p>★ <b>三句比较是【逐字相等】</b>，能这么写是因为那两句文案由本模块自己写进去、
     * 而且 {@code truncateComment} 只截 950 字以上的（那两句都短）。
     * ★★ 万一哪天文案改了一个字，这里的判断会<b>静默退化成 {@code RAN}</b> ——
     * 页面会把「模型未复核」当成「模型复核过了」。这是这条判据唯一的失效方式，
     * 所以它写在这里：<b>改那两句文案时，先回来读这一段。</b>
     * <p>★ 反过来，「{@code ai_comment} 为空 ⟺ 没调用过模型」这条<b>不是</b>从文案推出来的，
     * 它是 {@code AiAuditService#audit} 的构造事实：唯一一处传 {@code null}
     * 的是「关键词层无命中」那一支，而它连 AI 层都不进（那一支的注释写着「未调用 AI」）。
     */
    private static String aiState(String aiComment) {
        if (aiComment == null) {
            return "NOT_CALLED";
        }
        if (AiAuditService.AI_SKIPPED_COMMENT.equals(aiComment)) {
            return "SKIPPED";
        }
        if (AiAuditService.AI_NO_REASON_COMMENT.equals(aiComment)) {
            return "NO_REASON";
        }
        return "RAN";
    }

    /** ★ 四个 token 都有话；认不出来的（不该发生）返回 {@code null}，不回落成任何一句人话 */
    private static String aiStateDesc(String aiState) {
        if (aiState == null) {
            return null;
        }
        return switch (aiState) {
            case "NOT_CALLED" -> "未调用模型（关键词层无命中）";
            case "SKIPPED" -> "模型未复核（风险等级来自本地词库）";
            case "NO_REASON" -> "模型未给出理由";
            case "RAN" -> "模型已复核";
            default -> null;
        };
    }
}
