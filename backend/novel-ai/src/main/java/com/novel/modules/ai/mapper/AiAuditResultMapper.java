package com.novel.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.ai.entity.AiAuditResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * AI 审核结果 Mapper（E1/S8）。
 *
 * <h3>★★★ 那个「就地覆盖」为什么不走 {@code updateById} —— 这是一个具体的坑，不是风格偏好</h3>
 *
 * <p>本表每一个 {@code (target_type, target_id)} <b>最多只留一条【未处置】的行</b>
 * （{@code status=0}），新的判定到达时就地覆盖它。理由：
 * 队列是<b>待办</b>不是历史（历史由 {@code audit_log} 承担），
 * 两条待办会让人做第二次处置，于是产生一条<b>「没发生过的」人工流水</b>。
 *
 * <p>而覆盖必须能把 {@code audit_user_id} / {@code audit_time} / {@code ai_comment}
 * <b>写回 {@code NULL}</b>：v1 被人处置过（{@code audit_user_id=7}），
 * 作者改了内容重新提交，v2 的判定又进了队列 —— 这一行必须<b>重新变成没人处置过</b>，
 * 否则队列页会显示「已确认违规 · 处置人 7」，而那条结论是对 v1 说的。
 * ★★ 而 {@code updateById} 在这种「字段合法地变成 null」的情况下会
 * <b>静默跳过这一列</b>（P5 实施记录 ⑥ 记的就是它），于是 v2 显式带着 v1 的处置人 ——
 * <b>不报错、也能查，只是查出来的答案是错的。</b>
 * 所以这里用一条把每一列写在哪都摊开的显式 {@code UPDATE}。
 *
 * <h3>★ 为什么是「先 UPDATE 待办行，影响 0 行再 INSERT」而不是「先 SELECT 再决定」</h3>
 *
 * <p>一次往返，且<b>「有没有待办行」这件事的判据只有一条 SQL</b>：
 * {@code WHERE ... AND status=0}。拆成 SELECT + 分支的话，
 * 两次调用之间人正好点了「确认」（S9 的联动把 {@code status} 改成 1），
 * 那次 UPDATE 就会把一行的风险值改了、状态却还留着人的决定 ——
 * 而队列里<b>不会有这条新内容的待办</b>（因为 UPDATE 匹配上了，没走 INSERT）。
 * ★ 合并成一条语句之后，这种事只能得到「0 行受影响 → INSERT 一条新待办」，
 * 也就是<b>正确的那个结果</b>。
 *
 * <p>★★ {@code create_time} 由<b>调用方传进来</b>（{@code LocalDateTime.now()}），
 * 不是 {@code NOW()}：INSERT 那条路径的时间来自
 * {@code MybatisMetaObjectHandler}（<b>应用</b>的时钟），
 * 而 MySQL 跑在另一台机器上 —— 两条路径用两个时钟的话，
 * 「AI 判定时间」这个字段在同一张表里会混着两个时间源，
 * 而它们的偏差在跨时区部署时是<b>小时级</b>的，且没有任何报错。
 * （{@code create_time} 是 {@code FieldFill.INSERT}，自定义 {@code @Update} 不会触发填充，
 * 所以这一列必须显式写，写漏了它就会停在旧版本的时间上。）
 *
 * @author MickeyChen
 */
@Mapper
public interface AiAuditResultMapper extends BaseMapper<AiAuditResult> {

    /**
     * 覆盖该 target 的<b>未处置</b>那一行（{@code status=0}）。
     *
     * <p>★ 返回受影响行数：<b>{@code 0} 表示「这个 target 没有待办行」，调用方据此 INSERT。</b>
     * 不要把它当错误 —— 那是最常见的一条路径（第一次审）。
     *
     * @param status     ★ 一起写进去：无命中时是「机器排除」（{@code STATUS_EXCLUDED}），
     *                   命中时是 {@code STATUS_PENDING}。两者共用一个方法，
     *                   是因为它们对「旧待办行」的处置完全相同 —— 都必须覆盖掉。
     *                   ★★ 尤其是「命中 → 改成不命中」这一支（作者把敏感词删掉了再提交）：
     *                   不覆盖的话，队列里会永久留着一条<b>对新正文说的旧待办</b>。
     * @param createTime 判定时间，见类注释
     */
    @Update("""
            UPDATE ai_audit_result
               SET risk_level    = #{riskLevel},
                   hit_words     = #{hitWords},
                   ai_comment    = #{aiComment},
                   status        = #{status},
                   audit_user_id = NULL,
                   audit_time    = NULL,
                   create_time   = #{createTime}
             WHERE target_type = #{targetType}
               AND target_id   = #{targetId}
               AND status      = 0
            """)
    int overwritePending(@Param("targetType") Integer targetType,
                         @Param("targetId") Long targetId,
                         @Param("riskLevel") Integer riskLevel,
                         @Param("hitWords") String hitWords,
                         @Param("aiComment") String aiComment,
                         @Param("status") Integer status,
                         @Param("createTime") LocalDateTime createTime);

    /**
     * 人工处置联动（E1/S9）：把这条 AI 判定标成「人已经表过态了」。
     *
     * <p><b>★★★ 这一句是本判断（3.2）的核心，做了一次【反转】：</b>
     * 不是「管理员在队列页点确认，然后去写一条 {@code audit_log}」，
     * 而是 <b>「{@code audit_log} 被写出来了」这件事【本身】就是确认</b> ——
     * 于是联动挂在 {@code AuditLogServiceImpl#record} 那个<b>唯一汇聚点</b>上，
     * 覆盖章节审核页、评论管理页、以及经 MQ 来的那两条链路，一处挂载、全部生效。
     *
     * <h3>★★ 为什么不挂在队列页的那个按钮上</h3>
     *
     * <p>「确认违规」如果由队列页自己 {@code UPDATE chapter SET status=4}，
     * 就<b>绕开了 {@code ChapterServiceImpl#transitStatus}</b>（它的注释写着自己是
     * 「章节状态的唯一改动入口」）→ {@code audit_log} 缺一条、趋势图少一个点，
     * <b>而且不报错</b>。挂在流水上则是：能写流水的那条路，必然已经过了状态机。
     *
     * <h3>★ 为什么 {@code status=0} 是 {@code WHERE} 里的一部分</h3>
     *
     * <p>它是<b>乐观条件</b>，一次性买到三件事：
     * <ol>
     *   <li><b>幂等</b>：同一次处置重放（MQ 重投）影响 0 行，不会把一条已确认的改回去；</li>
     *   <li><b>顺序无关</b>：两条并发的处置只有一条能改到这行，另一条影响 0 行；</li>
     *   <li>★★ <b>不覆盖「已处置」的行</b> —— 这是最要紧的一条：
     *       {@code overwritePending} 也会写 {@code status}，若这里不限定 {@code status=0}，
     *       一次迟到的处置就会把<b>已经被人排除过</b>的那行改成「已确认」，
     *       而处置人还是原来那个 —— 一句<b>替别人认领的、他没做过的动作</b>。
     * </ol>
     * ★ <b>{@code 0} 行受影响不是错误</b>：那是「这条判定已经不是待办了」，
     * 调用方据此决定要不要吱一声（见 {@code AuditLogServiceImpl} 的用法）。
     *
     * <h3>★ 两处刻意的「不写」</h3>
     *
     * <ul>
     *   <li>★ <b>不写 {@code risk_level} / {@code hit_words} / {@code ai_comment}</b>：
     *       人的处置不改变「AI 当时判了什么」。改掉它们会让队列变成一份
     *       <b>看不出模型对错的</b>记录 —— 而「模型判了多少、其中多少是误杀」
     *       正是这张表存在的理由。</li>
     *   <li>★ <b>不写 {@code create_time}</b>：它是「AI 判定时间」，被这次 UPDATE
     *       刷成处置时间的话，队列页上「判定时间 vs 内容更新时间」这个比照就废了
     *       （那两列是判断 3.2 里替代 {@code content_hash} 的东西）。
     *       处置时间走 {@code audit_time}，两列各说各的。</li>
     * </ul>
     *
     * <p>★★ <b>{@code create_time} 上还有一层坑</b>：它是 {@code FieldFill.INSERT}，
     * 而自定义 {@code @Update} <b>不触发</b> {@code MybatisMetaObjectHandler} ——
     * 这条语句里没提它，它就<b>保持原值</b>。这正是想要的，但它是「因为没写所以对」，
     * 所以要写在这里：<b>将来谁往 SET 里补一列，先回来读这段。</b>
     *
     * @param targetType  ★ 调用方传的是 {@code AuditTargetType} 的码。它与
     *                    {@code ai_audit_result.target_type} 的 1/2/3 对齐；
     *                    {@code AUTHOR_APPLY(4)} <b>天然命中 0 行</b>（本表没有 4），
     *                    所以调用方<b>不需要</b>按类型分流 —— 那种分支迟早会和
     *                    「AI 审核扩展到新类型」这件事对不上，症状是<b>队列里
     *                    永久留着一条待办</b>。让 WHERE 去判，判据只有一处。
     * @param status      ★ 只接受两个值：{@code STATUS_CONFIRMED(1)}（驳回 = 确认违规）
     *                    与 {@code STATUS_EXCLUDED(2)}（通过 = 排除误杀）。
     *                    {@code STATUS_PENDING(0)} 传进来是调用方的 bug。
     * @param auditUserId 处置人（= 流水里的 {@code operator_id}）
     * @param auditTime   处置时刻。★ 传的是<b>流水的 {@code create_time}</b>，
     *                    不是 {@code now()}：MQ 有积压时「管理员什么时候审的」
     *                    和「这条流水什么时候被消费的」差得很远，而队列页上
     *                    这一列要回答的是前者（同 {@code AuditLogService#record} 的论证）。
     * @return 受影响行数。<b>0 = 这条判定本来就不是待办了（或压根没有过）</b>
     */
    @Update("""
            UPDATE ai_audit_result
               SET status        = #{status},
                   audit_user_id = #{auditUserId},
                   audit_time    = #{auditTime}
             WHERE target_type = #{targetType}
               AND target_id   = #{targetId}
               AND status      = 0
            """)
    int linkHumanDisposition(@Param("targetType") Integer targetType,
                             @Param("targetId") Long targetId,
                             @Param("status") Integer status,
                             @Param("auditUserId") Long auditUserId,
                             @Param("auditTime") LocalDateTime auditTime);
}
