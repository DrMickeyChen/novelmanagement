package com.novel.modules.ai.vo;

import com.novel.modules.ai.entity.AiUsageLog;
import com.novel.modules.ai.enums.AiFailure;
import com.novel.modules.ai.enums.AiScene;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条 AI 调用流水（E1 用量页）。
 *
 * <h3>★★★ 这个 VO 最要紧的字段是 {@link #tokenUntracked}</h3>
 *
 * <p>「这次调用花了多少 token」这句话，在这张表上<b>有一个说不出口的情况</b>：
 * 用量（usage）是上游在<b>流的末尾</b>才发的，而中断 / 报错 / 网络断掉的调用
 * 根本走不到那一帧。可 {@code prompt_tokens} 那三列是
 * {@code INT NOT NULL DEFAULT 0} —— <b>结构上放不下「未知」</b>。
 *
 * <p>于是那些行的 token 列读出来是 {@code 0}，而 {@code 0} 在界面上
 * 与「这次没花钱」<b>长得一模一样</b>。这正是本项目一路在猎的东西：
 * 一个看起来正常、方向还偏向「更省钱」的假数据。
 *
 * <p>★ 处置：与其让页面自己判断「这行的 0 是不是假的」，
 * 不如<b>把判断结果一起送出去</b>（{@code tokenUntracked}）。
 * 页面不维护规则，只渲染。
 *
 * <p>★★ 判据与 {@code AiUsageSummaryVO} 里那个计数<b>逐字一致</b>
 * （{@code success = 1 AND total_tokens = 0}），这不是巧合：
 * 列表和汇总必须能对得上，否则「今天有 3 次未统计」这句话在明细里
 * <b>一行都找不到</b> —— 那比不显示还糟。
 *
 * <h3>★ 为什么失败的行连 token 都不显示</h3>
 *
 * <p>失败行的 token 列<b>不承担「消费量」的语义</b>：有的确实是 0（401 压根没生成），
 * 有的是「烧了但不知道」。既然分不出来，就<b>不假装分</b> ——
 * 页面把它们渲染成 {@code —}，把「不知道」诚实地摆出来。
 *
 * <h3>★ 为什么 {@code userName} 可能是 {@code null}</h3>
 *
 * <p>它来自 {@code ContentUserLookup.nicknamesOf(...)} —— 一次<b>跨服务</b>调用，
 * 而那条接缝在 auth 挂掉时<b>降级成空 Map</b>（{@code AuthUserClientFallbackFactory}：
 * {@code nicknamesOf} → {@code logThenDegrade(..., emptyMap())}），
 * 所以这里拿到 {@code null} <b>不会</b>让整页 500。
 * 于是 {@code userName} 为 null → 全局 {@code default-property-inclusion: non_null}
 * 让这个字段<b>从 JSON 里消失</b> → 页面回落显示「用户 #7」。
 *
 * <p>★★ <b>这个消费方式是那条接缝的既有判定覆盖到的，E1 不需要重新判</b> ——
 * {@code AuthUserClientFallbackFactory} 的类注释列了它两个消费者的兜底文案，
 * 其中「{@code operatorName || '用户 #' + id}」被判为<b>真话</b>
 * （它说的是「这是 7 号用户，只是没拿到名字」）。
 * 本 VO 用的是<b>同一句话</b>，所以直接站得住。
 *
 * <p>★ 而「佚名」在这里<b>不能</b>用：它是给 {@code NovelVO.authorName} 的兜底，
 * 那句话在那边成立是因为作者的昵称<b>可能真的查不到</b>；
 * 放在这儿就成了「查到了、但这个人没有名字」——
 * 一个关于这个人的**具体断言**，而它没有被验证过。
 * ★ 判据还是那条：<b>兜底文案说的是「我们知道什么」，还是在替数据下结论。</b>
 * （同一张表里的「（已删除）」就是后者，也是 M5 唯一被判成假话的那个。）
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "一条 AI 调用流水")
public class AiUsageVO {

    @Schema(description = "流水 id")
    private Long id;

    @Schema(description = "调用时间")
    private LocalDateTime createTime;

    @Schema(description = "场景码：1大纲 2续写 3灵感 4润色 5审核", example = "2")
    private Integer scene;

    /** ★ 由 {@code AiScene.getLabel()} 给出。页面不维护映射表（枚举注释的明文要求） */
    @Schema(description = "场景名", example = "续写正文")
    private String sceneDesc;

    @Schema(description = "实际调用的模型名", example = "deepseek-chat")
    private String model;

    @Schema(description = "调用人 id")
    private Long userId;

    /** ★ 可能为 null（跨服务取昵称失败 → 降级）。页面据此回落「用户 #id」 */
    @Schema(description = "调用人昵称。取不到时不返回该字段，页面回落显示「用户 #id」")
    private String userName;

    /** ★ 可能为 null：场景不针对具体作品时（目前只有审核会带，流式四场景都不带） */
    @Schema(description = "关联作品 id（可为空）")
    private Long novelId;

    @Schema(description = "关联章节 id（可为空）")
    private Long chapterId;

    @Schema(description = "输入 token。★ 见类注释：失败行与 tokenUntracked 行上它不代表消费量")
    private Integer promptTokens;

    @Schema(description = "输出 token。★ 同上")
    private Integer completionTokens;

    @Schema(description = "合计 token。★ 同上")
    private Integer totalTokens;

    /**
     * ★★ 这一行的 token <b>没有被统计到</b>（不是「没消耗」）。
     *
     * <p>判据：{@code success = 1 AND total_tokens = 0}。
     * 一次真的调用，{@code prompt_tokens} 不可能是 0（我们永远会发非空的提示词），
     * 所以「成功了却是 0」只可能是用量帧没拿到。
     */
    @Schema(description = "true = 这次调用确实发生了、但用量没统计到（页面显示「未统计」而不是 0）")
    private Boolean tokenUntracked;

    @Schema(description = "耗时（毫秒）")
    private Integer durationMs;

    /** ★ 存储里是 TINYINT，这里给布尔 —— {@code 0/1} 是存储细节，不该漏到接口上 */
    @Schema(description = "是否成功")
    private Boolean success;

    /** ★ 白名单常量，绝不可能是上游原文（见 {@code AiFailure} 的类注释） */
    @Schema(description = "失败原因码（仅失败行有）", example = "timeout")
    private String errorMsg;

    /** ★ 由 {@code AiFailure.getLabel()} 给出；认不出来的 code 原样回落（见 {@code AiFailure.fromCode}） */
    @Schema(description = "失败原因（人话）")
    private String errorDesc;

    /**
     * 装配一行。<b>只做转换，不做任何查询</b> —— 昵称由调用方批量取好传进来。
     *
     * <p>★ 这条纪律抄自 {@code AuditLogVO.of}：一旦这里能查库，
     * 一个分页列表就会变成 N 次查询，而且<b>在代码上看不出来</b>
     * （它长得像一个纯粹的构造函数）。
     *
     * @param log      流水行
     * @param userName 调用人昵称；<b>允许为 null</b>（跨服务取不到时的降级值）
     */
    public static AiUsageVO of(AiUsageLog log, String userName) {
        AiUsageVO vo = new AiUsageVO();
        vo.setId(log.getId());
        vo.setCreateTime(log.getCreateTime());
        vo.setScene(log.getScene());
        vo.setSceneDesc(sceneDesc(log.getScene()));
        vo.setModel(log.getModel());
        vo.setUserId(log.getUserId());
        vo.setUserName(userName);
        vo.setNovelId(log.getNovelId());
        vo.setChapterId(log.getChapterId());
        vo.setPromptTokens(log.getPromptTokens());
        vo.setCompletionTokens(log.getCompletionTokens());
        vo.setTotalTokens(log.getTotalTokens());
        vo.setDurationMs(log.getDurationMs());
        vo.setSuccess(log.getSuccess() != null && log.getSuccess() == 1);

        /*
         * ★★ 判据与 AiUsageSummaryVO.untrackedTokenCalls 逐字一致 —— 见类注释。
         *   ★ 写成「成功且合计为 0」而不是「合计为 0」，是因为失败行里
         *     大量是「压根没开始生成」的真 0（401/404/连不上），
         *     把它们也算进「未统计」会让那个数字在故障天里失去意义
         *     （全部失败 → 全部算未统计 → 管理员看到的是一句废话）。
         */
        vo.setTokenUntracked(vo.getSuccess() && (log.getTotalTokens() == null || log.getTotalTokens() == 0));

        vo.setErrorMsg(log.getErrorMsg());
        vo.setErrorDesc(errorDesc(log.getErrorMsg()));
        return vo;
    }

    /**
     * 失败码 → 人话。
     *
     * <p>★ 认不出来的 code <b>原样返回</b>，不翻译成「未知错误」：
     * 前者是「这行是另一个版本的代码写的」（能直接看出是哪个版本加的常量），
     * 后者是「当时我也不知道出了什么事」（{@code AiFailure.UNKNOWN} 的语义）——
     * <b>把两种「不知道」合并成一句话，就等于把排查线索丢掉了一半。</b>
     */
    private static String errorDesc(String errorMsg) {
        if (errorMsg == null || errorMsg.isEmpty()) {
            return null;
        }
        AiFailure failure = AiFailure.fromCode(errorMsg);
        return failure != null ? failure.getLabel() : errorMsg;
    }

    /**
     * 场景码 → 场景名。
     *
     * <p>★ 认不出来时返回 {@code null}（页面显示「-」），
     * <b>不抛异常</b> —— 理由同 {@code AiFailure.fromCode}：输入是数据库里的历史行，
     * 不是调用方给的。★ 也不能返回「未知场景」这种看着像正常取值的字符串。
     */
    private static String sceneDesc(Integer scene) {
        if (scene == null) {
            return null;
        }
        try {
            return AiScene.fromCode(scene).getLabel();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
