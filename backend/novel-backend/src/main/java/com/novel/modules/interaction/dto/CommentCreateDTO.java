package com.novel.modules.interaction.dto;

import com.novel.common.constant.CommonConstant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论的入参。
 *
 * <p><b>★★ 这个 DTO 上没有 {@code userId}，也没有 {@code status}、没有 {@code novelId}。</b>
 * <ul>
 *   <li>{@code userId} —— 评论人只能来自 {@code UserContext}。
 *       放进请求体，即使 Service 里「记得」忽略它，也<b>只差一次重构</b>
 *       就会变成「以任意用户的身份发言」—— 那是伪造他人言论。
 *       字段不存在，这条路在类型层面就是封死的（同 {@code HistoryReportDTO}).</li>
 *   <li>{@code status} —— 评论只能是「待审核」。
 *       让调用方能指定状态，就是让读者可以自己把评论设成「已显示」，
 *       审核形同虚设。<b>不要为了「测试方便」开这个口子</b>。</li>
 *   <li>{@code novelId} —— 它在 URL 路径上，不在 body 里。
 *       body 里再放一份就会出现「路径说 A、body 说 B」时以谁为准的问题，
 *       而无论选哪个，另一份从此就是骗人的。</li>
 * </ul>
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "发表评论入参")
public class CommentCreateDTO {

    /**
     * 评论正文。
     *
     * <p><b>★ 上限用 {@code CommonConstant.COMMENT_MAX_LENGTH} 而不是写死 500：</b>
     * 这个常量是编译期的 {@code static final int}，可以直接用在注解里
     * （{@code Integer} 就不行 —— 注解成员只接受基本类型），
     * 而列宽 {@code VARCHAR(1000)}（按字符算最多 1000）也留了余量。
     * <b>但数据库那一列不是防线</b>：它对超长字符串的处理依 SQL 模式而定，
     * 严格模式下报错（用户看到 500），非严格模式下静默截断 ——
     * 两种都不该让用户碰到，所以长度必须在进库之前就拦住。
     *
     * <p>★ {@code @NotBlank} 而不是 {@code @NotNull}：
     * 后者放行 {@code "   "}，于是一条空白评论可以进库并占用待审队列，
     * 管理员看到的是一个点不动的空白条目。
     */
    @Schema(description = "评论内容，最长 500 字", example = "主角的心路历程写得很细腻，追更中。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "评论内容不能为空")
    @Size(max = CommonConstant.COMMENT_MAX_LENGTH,
            message = "评论最多 " + CommonConstant.COMMENT_MAX_LENGTH + " 个字")
    private String content;

    /**
     * 评分，1~5 分，<b>可选</b>（不传 = 不打分）。仅书评有。
     *
     * <p><b>★★ 这里用 {@code @Min/@Max} 直接拒掉越界值，而不是像
     * {@code HistoryReportDTO#progress} 那样「夹到边界」——两者的判据不同：</b>
     * <ul>
     *   <li>{@code progress} 是前端用 {@code scrollTop / (scrollHeight - clientHeight)}
     *       算出来的，取整、字体加载导致的高度变化、缩放都会让它短暂越界一两个点。
     *       那是<b>合法的浮点误差</b>，为它拒掉整条进度是错的。</li>
     *   <li>{@code score} 来自一个五个选项的控件（1~5 星）。
     *       出现 {@code 99} 或 {@code 0} 说明<b>调用方坏了</b>，不是误差。
     *       而且它会进 {@code novel.score} 那个平均分（P6 维护），
     *       夹一下就把一个错误的分数洗成了合法的分数 ——
     *       错的东西看起来对了，比报错难查得多。</li>
     * </ul>
     * <p>换句话说：<b>能被夹的，是「值本身有误差」；不能被夹的，是「值本身就是错的」。</b>
     */
    @Schema(description = "评分 1~5，不传表示不打分", example = "5")
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer score;
}
