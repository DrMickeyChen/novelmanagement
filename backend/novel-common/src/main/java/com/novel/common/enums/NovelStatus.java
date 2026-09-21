package com.novel.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * 作品状态机。见 {@code 项目大纲.md §5.3}。
 *
 * <pre>
 *   0 草稿 ──提交审核──▶ 1 待审核 ──管理员通过──▶ 2 已上架
 *      ▲                   │  │                   │
 *      │                   │  └──管理员驳回──▶ 4 已驳回   管理员下架
 *      │                   │                     │         ▼
 *      │              ★作者撤回提交              │
 *      └───────────────────┘                     └──▶ 0 草稿   3 已下架
 *                                                              │
 *                                                         重新上架
 *                                                              ▼
 *                                                            2 已上架
 * </pre>
 *
 * <p><b>为什么要用枚举而不是散落的 0/1/2/3/4：</b>
 * 判断状态的地方会有几十处，写成 {@code if (novel.getStatus() == 2)}
 * 三个月后没人记得 2 是什么；而 {@code NOVEL_STATUS_ILLEGAL} 这类
 * 非法流转必须在 Service 层拦住，用一个 {@link #canTransitTo} 方法
 * 比到处写 if 靠谱得多。
 *
 * @author MickeyChen
 */
@Getter
public enum NovelStatus {

    DRAFT(0, "草稿"),
    PENDING(1, "待审核"),
    PUBLISHED(2, "已上架"),
    OFFLINE(3, "已下架"),
    REJECTED(4, "已驳回");

    /** 存进数据库的值 */
    @EnumValue
    @JsonValue
    private final Integer code;

    /** 给用户看的文案 */
    private final String desc;

    NovelStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按数字码取枚举，用于解析<b>查询参数</b>（如 {@code ?status=2}）。
     *
     * <p><b>★ 为什么不靠 Spring 的类型转换：</b>Spring 对枚举 query 参数走的是
     * {@code Enum.valueOf}，也就是按<b>名字</b>匹配 —— 传 {@code ?status=2} 会失败，
     * 必须传 {@code ?status=PUBLISHED}。而 JSON body 里的 {@code status}
     * 因为 {@code @JsonValue} 走的是<b>数字</b>（见 {@code NovelStatusDTO} 的注释）。
     * 同一个字段，body 里传数字、query 里传名字，
     * 这种「一个字段两种写法」是前端 bug 的温床。所以统一成数字，
     * 由 controller 显式调本方法转换。见 {@code NovelStatusDTO} 的类注释。
     *
     * <p><b>★ 入参非法时抛的是 {@code BusinessException(PARAM_ERROR)} 而不是
     * {@code IllegalArgumentException}，这一点很要紧：</b>
     * {@code ?status=99} 是<b>调用方传错了</b>，不是「程序有 bug」。
     * 抛 {@code IllegalArgumentException} 的话它会冒到
     * {@code GlobalExceptionHandler} 最底下的兜底分支，
     * 于是返回 <b>HTTP 500 + 一条带完整堆栈的 ERROR 日志</b> ——
     * 扫描器随便试几个参数值就能把日志刷满假警报，而监控上看着像后端挂了。
     * 现在是 400 + 一句「未知的作品状态」，warn 级别，与其它参数错误一致。
     */
    public static NovelStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的作品状态：" + code));
    }

    /**
     * 当前状态能不能流转到目标状态。
     *
     * <p>所有改状态的接口都必须先过这一关，例如：
     * <pre>
     * if (!novel.getStatus().canTransitTo(NovelStatus.PUBLISHED)) {
     *     throw new BusinessException(ResultCode.NOVEL_STATUS_ILLEGAL);
     * }
     * </pre>
     * 少了这一句，「已上架的作品又被提交审核」这种脏数据就会进库，
     * 后面查起来非常痛苦。
     */
    public boolean canTransitTo(NovelStatus target) {
        return switch (this) {
            // 草稿可以提交审核，也可以直接删除（不在这里表达）
            case DRAFT -> target == PENDING;
            /*
             * 待审核的三条出路：
             *   → PUBLISHED  管理员通过
             *   → REJECTED   管理员驳回
             *   → DRAFT      作者自己「撤回提交」（P4 补的）
             *
             * ★ 为什么作者撤回是安全的、不会绕过审核：
             *   撤回只是把书从【审核队列里拿走】，让它回到作者手里继续改。
             *   审核员打开的那一刻，这条记录还在 PENDING ——
             *   撤回和「通过」是两条互斥的 UPDATE，
             *   谁先提交谁生效，后到的那条会读到已经变了的状态而失败。
             *   真正危险的是【改内容】而不是【改状态】，
             *   而改内容被 isEditable() 挡着（PENDING 不可编辑），
             *   撤回之后它才变成可编辑的 —— 顺序是对的。
             */
            case PENDING -> target == PUBLISHED || target == REJECTED || target == DRAFT;
            // 已上架：只能下架（要改内容得先下架，或走「退回草稿」）
            case PUBLISHED -> target == OFFLINE;
            /*
             * 已下架的两条出路，两条都要有：
             *   → PUBLISHED  管理员的「重新上架」。上面那张图里的正是这条。
             *   → PENDING    作者主动「重新提交审核」（比如想借机改内容）
             *
             * ★ 这里的 `|| target == PUBLISHED` 是后补的：这段 switch 最初
             *   只写了 PENDING，和本类开头那张 ASCII 图**直接矛盾** ——
             *   图上明明画着「已下架 ──重新上架──▶ 已上架」，
             *   但代码不允许 3 → 2，于是管理端的「重新上架」接口
             *   一调就是 2003「当前作品状态不允许该操作」，功能根本做不出来。
             *   这种「注释里的图和代码打架」是最难发现的一类 bug：
             *   读代码的人信代码，读注释的人信注释，两边都觉得自己是对的。
             */
            case OFFLINE -> target == PENDING || target == PUBLISHED;
            // 已驳回：作者改完可以重新提交
            case REJECTED -> target == PENDING;
        };
    }

    /** 读者能不能看到这个作品（只有已上架的对外可见） */
    public boolean isVisibleToReader() {
        return this == PUBLISHED;
    }

    /**
     * 作者能不能编辑这个作品的信息（书名 / 简介 / 分类 / 标签）。
     *
     * <p><b>为什么这件事要写在枚举上：</b>它和第 5 条约束一样，是「状态决定行为」
     * 的规则，散在各个 Service 里写 {@code if (status == 0 || status == 4 || ...)}
     * 迟早会有人漏掉一种情况。
     *
     * <p><b>被排除的两个状态，各自有明确理由：</b>
     * <ul>
     *   <li>{@code PENDING} 待审核 —— <b>这是最要紧的一条。</b>
     *       审核员打开的是 A 版的简介，作者在审核期间改成 B 版，
     *       审核员点通过，上架的是 B —— <b>审核被绕过了</b>，
     *       而日志上看起来是一次完全正常的审核。</li>
     *   <li>{@code PUBLISHED} 已上架 —— 已上架的作品读者正在看，
     *       改简介会让书架上的书「变了样」。要改就先下架
     *       （{@code OFFLINE} 是可编辑的，所以下架后就能改）。</li>
     * </ul>
     *
     * <p><b>★ P4 补上了当初记在这里的那个缺口：</b>原本作者提交后发现简介有错别字，
     * 在审核出结果前改不了，只能等管理员驳回 —— 现在有了「撤回提交」
     * （{@code PENDING → DRAFT}，见 {@link #canTransitTo}），
     * 撤回之后状态变回 {@code DRAFT}，本方法返回 true，就能改了。
     * <b>缺口的解法是「先撤回再改」，而不是「让 PENDING 也能改」</b> ——
     * 后者才是绕过审核，前者只是把书从队列里拿出来，顺序上没有漏洞。
     */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED || this == OFFLINE;
    }
}
