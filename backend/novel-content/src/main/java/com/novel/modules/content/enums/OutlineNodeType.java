package com.novel.modules.content.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * 大纲节点类型，对应 {@code novel_outline.node_type}。见 {@code 项目大纲.md §7.3}。
 *
 * <pre>
 *   1 全书 ──▶ 2 卷 ──▶ 3 章 ──▶ 4 细纲
 *   （根节点）                    （叶子，可以无限嵌套）
 * </pre>
 *
 * <p><b>★ 放在 {@code modules/content/enums/} 而不是 {@code common/enums/}：</b>
 * {@code NovelStatus} / {@code ChapterStatus} 放 common，是因为它们被
 * content 和 admin 两个模块同时引用。而大纲类型只有创作中心用 ——
 * 「一个包 = 一个将来要拆出去的微服务」，放进 common 就等于
 * 让 novel-content 之外的服务也看得见它，是没必要的耦合。
 *
 * <p><b>★ 这个枚举不参与任何校验</b>（见 P4 方案的决定 9）：
 * 「细纲下面能不能再挂细纲」「卷下面能不能直接挂细纲」都不拦。
 * 理由：这类规则<strong>能靠前端按类型隐藏按钮来规避</strong>，
 * 而做成后端错误码之后，作者某天想在「卷」下面直接写一条设定
 * （不建章节）就会被拦住 —— 那是他的大纲，不是我们的。
 * 这个枚举真正的用途只有两个：前端显示哪个图标、以及
 * 「新建子节点时默认建什么类型」（见 {@link #defaultChildOf}）。
 *
 * @author MickeyChen
 */
@Getter
public enum OutlineNodeType {

    /** 全书 —— 一本书有且只有一个根节点 */
    BOOK(1, "全书"),
    /** 卷 / 分卷 */
    VOLUME(2, "卷"),
    /** 章 —— 通常对应一个正文章节，但不是必须（见决定 9） */
    CHAPTER(3, "章"),
    /** 细纲 / 情节 —— 叶子节点，可以无限嵌套 */
    DETAIL(4, "细纲");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String desc;

    OutlineNodeType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按数字码取枚举，和 {@code NovelStatus#fromCode} 同一套规则：
     * 非法值抛 {@code PARAM_ERROR(400)} 而不是 {@code IllegalArgumentException}
     * （理由见 {@code NovelStatus#fromCode} 的注释）。
     *
     * <p>★ {@code nodeType} 是可以由前端直接传进来的（建节点接口），
     * 所以这里是真的会被调用到，不是摆设。
     */
    public static OutlineNodeType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(t -> t.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的大纲节点类型：" + code));
    }

    /**
     * 在某类节点下面新建子节点时，<b>默认</b>建什么类型。
     *
     * <p><b>★ 注意这个方法的名字是 defaultChildOf，不是 childOf：</b>
     * 它给的是前端「新建子节点」菜单的默认选中项，<b>不是校验规则</b>。
     * 作者在「卷」下面手动选了「细纲」，后端一样接受（见类注释）。
     * <p>命名上把这件事说清楚，是为了防止后来者看到这个方法
     * 就以为「子节点类型是后端定死的」，进而拿它去做校验 ——
     * 那就把决定 9 悄悄推翻了。
     *
     * <p>{@code DETAIL} 的子节点还是 {@code DETAIL}：细纲是叶子的语义，
     * 但允许继续往下细分（「这一章分三个场景」）。
     */
    public OutlineNodeType defaultChildOf() {
        return switch (this) {
            case BOOK -> VOLUME;
            case VOLUME -> CHAPTER;
            case CHAPTER, DETAIL -> DETAIL;
        };
    }
}
