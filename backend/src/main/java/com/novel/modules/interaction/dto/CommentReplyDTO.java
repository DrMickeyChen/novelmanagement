package com.novel.modules.interaction.dto;

import com.novel.common.constant.CommonConstant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 回复评论的入参。<b>只有一个字段 —— 这是这个类存在的全部理由。</b>
 *
 * <p><b>★★ 为什么不复用 {@link CommentCreateDTO}：</b>
 * 那个上面有 {@code score}，而回复<b>不能打分</b>。
 * 复用的话只有两种改法，都不可接受：
 * <ul>
 *   <li>「Service 里记得忽略回复的 score」—— 靠人记住的事迟早会忘，
 *       而忘掉的后果是 {@code novel.score} 被「嗯」「+1」这类回复
 *       一条条稀释掉：评分看着还在<b>动</b>，只是越动越低，没人会怀疑；</li>
 *   <li>「回复接口也接受 score」—— 那就更直接了：打分变成了一件
 *       可以在一本书下面无限次重复做的事，刷分就是这么开始的。</li>
 * </ul>
 * <b>字段不存在，这条路在类型层面就是封死的</b> ——
 * 和 {@code CommentCreateDTO} 不设 {@code userId} / {@code status} 是同一条规矩。
 *
 * <p><b>★ 同样【没有】parentId：</b>它在 URL 路径上
 * （{@code POST /api/comments/{parentId}/replies}）。
 * body 里再放一份就会出现「路径说 A、body 说 B」时以谁为准的问题，
 * 而无论选哪个，另一份从此就是骗人的。
 *
 * <p><b>★ 也【没有】targetType / targetId：</b>那两个从父评论<b>继承</b>，
 * 理由见 {@code CommentService#reply} —— 让调用方填，就必然出现
 * 「父是书评、子是章评」的行，而它在列表里表现为这条回复根本不显示。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "回复评论入参")
public class CommentReplyDTO {

    /**
     * 回复正文。长度规则和发表评论完全一致
     * （{@code @NotBlank} 挡住 {@code "   "}，上限走常量）。
     *
     * <p>★ 刻意没有单独的、更短的上限：回复和评论在本项目里是同一类内容，
     * 一条 500 字的回复是长，但不比一条 500 字的评论更需要拦。
     * 为它多维护一个常量，换来的只是「两处长度不一致」这个坑。
     */
    @Schema(description = "回复内容，最长 500 字", example = "同感，第 12 章那段我也看了两遍。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "回复内容不能为空")
    @Size(max = CommonConstant.COMMENT_MAX_LENGTH,
            message = "回复最多 " + CommonConstant.COMMENT_MAX_LENGTH + " 个字")
    private String content;
}
