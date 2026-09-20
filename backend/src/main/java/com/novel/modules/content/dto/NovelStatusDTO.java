package com.novel.modules.content.dto;

import com.novel.common.enums.NovelStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端「上架 / 下架」入参。
 *
 * <p><b>★ 这里可以收目标状态，因为上下架本身就是「指定一个新状态」的动作</b> ——
 * 和审核（{@code AuditDTO}）不同，审核收的是「通过/驳回」这种业务意图。
 * 两者的区别在于：审核的语义是「这次审的结果怎样」，上下架的语义就是「把它设成什么」。
 *
 * <p><b>★ {@code status} 的线上格式是数字，不是枚举名 —— 这一点实测过，不要凭直觉写：</b>
 * <pre>
 *   传 {"status": 2}            → 正常，反序列化成 NovelStatus.PUBLISHED
 *   传 {"status": "PUBLISHED"}  → HTTP 400，code 400「请求内容格式不正确」
 *   传 {"status": 99}           → HTTP 400，code 400「请求内容格式不正确」
 * </pre>
 * 原因是 {@code NovelStatus} 的 {@code code} 字段上标了 {@code @JsonValue}，
 * 而 Jackson 的 {@code @JsonValue} 在枚举上是<b>双向生效</b>的：
 * 出参按它序列化，入参也按它反序列化（内部按各枚举常量的值建一张反查表）。
 * 网上大量文章说「{@code @JsonValue} 只管序列化、反序列化要用 {@code @JsonCreator}」——
 * <b>那是旧版本 Jackson 或者非枚举类型的情况，对枚举不成立。</b>
 * 本项目的实际行为以上面三行为准。
 *
 * <p><b>这个契约其实是自洽的，不是缺陷：</b>存库（{@code @EnumValue} → 2）、
 * 出参（{@code @JsonValue} → 2）、入参（{@code @JsonValue} 反查 → 2）
 * 三处都是同一个数字，前端只要把列表接口返回的 {@code status}
 * 原样回传就能改状态，不用做任何映射。
 *
 * <p><b>非法值会被挡住，而且是 400 不是 200：</b>传 99 时没有任何枚举常量匹配，
 * Jackson 抛 {@code InvalidFormatException}，被 Spring 包成
 * {@code HttpMessageNotReadableException}，交给
 * {@code GlobalExceptionHandler#handleNotReadable} 返回
 * HTTP 400 + {@code code 400}。也就是说<b>前端传错格式会立刻在联调时暴露</b>，
 * 不会静默存一个 99 进库 —— 这正是「用枚举类型而不是 {@code Integer}」换来的。
 *
 * <p><b>为什么不再加一个 {@code @JsonCreator} 来兼容枚举名：</b>
 * 加了之后 {@code "PUBLISHED"} 和 {@code 2} 都能收，看着更宽容，
 * 但那是<b>把两套表示法都变成合法输入</b> —— 从此前端两种写法混着用，
 * 谁也不知道该以哪个为准；而 Jackson 遇到「既有 {@code @JsonValue} 又有
 * {@code @JsonCreator}」时的解析顺序还依赖具体版本。收一种、说清楚是哪一种，
 * 比两种都收要好。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "上下架入参")
public class NovelStatusDTO {

    @Schema(description = "目标状态：2 上架 / 3 下架。★ 传数字，不要传 \"PUBLISHED\" 这类枚举名",
            example = "2", allowableValues = {"2", "3"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请指明目标状态")
    private NovelStatus status;

    @Schema(description = "备注：下架时可写明原因，会记进审核日志", example = "内容违规，暂时下架整改")
    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
