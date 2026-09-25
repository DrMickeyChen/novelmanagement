package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 词库快照的状态（E1/S7）—— <b>把「改了没生效」变成一个能看见的数字</b>。
 *
 * <h3>★★★ 这个 VO 存在的唯一理由：多实例下陈旧是【无法消除】的，只能让它可见</h3>
 *
 * <p>本项目<b>刻意不接 Nacos Config</b>（判断 2.4），所以词库的热更新只能是
 * 「每个实例各自 reload 自己的进程内快照」。两个实例之间<b>没有</b>任何机制保证
 * 它们的快照是同一份 —— 也不该硬造一个（那要么是分布式锁，要么是让 content 去订阅
 * 一个配置中心，两者都比它解决的问题大）。
 *
 * <p>→ ★ <b>诚实的做法不是假装解决它，而是让「这一份有多旧」能被看见。</b>
 * 这正是铁律 10 的「消费方能不能诚实地把这个缺失渲染出来」。
 * 词库管理页把 {@link #enabledRows}（库里）和 {@link #wordCount}（快照里）并排放，
 * 两个数不相等就说明<b>有人改了库但还没 reload</b>；{@link #loadedAt} 说明这一份是什么时候的。
 *
 * <h3>★★ 两个布尔值的区别不是凑数</h3>
 *
 * <p>{@link #loaded} 与 {@link #ready} 落在 fail-closed 的<b>两个不同分支</b>上
 * （判断 3.5 的三种情况里的前两种），而它们的日志级别、修法都不同：
 * <pre>
 *   loaded=false  → 加载【失败】      → 记 ERROR，去查 DB / 连接
 *   loaded=true 且 wordCount=0 → 词库【是空的】→ 记 WARN，去录词
 *   ready = loaded && wordCount > 0 → 唯一的「能审」状态
 * </pre>
 * ★ 合成一个布尔值的话，「还没录词」会被报成「加载失败」，
 * 管理员会去查数据库连接 —— 而真正该做的是加一个词。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "敏感词库快照状态")
public class SensitiveWordStatusVO {

    /**
     * 快照是否<b>存在</b>。
     *
     * <p>★ 这是「加载成功过至少一次」的痕迹，与「快照里有几条词」是两件事。
     * ★★ 一次失败的 reload <b>不会</b>把它清成 false —— 见
     * {@code SensitiveWordScanner#reload} 的类注释「加载失败不销毁一份有效的旧快照」。
     */
    @Schema(description = "快照是否存在（false = 从没加载成功过）")
    private Boolean loaded;

    /**
     * 是否<b>可以审</b>：{@code loaded && wordCount > 0}。
     *
     * <p>★★ <b>它是唯一允许放行的状态。</b> 为 false 时所有的审核调用
     * 一律以 {@code AI_WORD_LIB_UNAVAILABLE(5007)} 拒掉（fail-closed）。
     */
    @Schema(description = "是否可审：false 时所有审核会被拒（fail-closed）")
    private Boolean ready;

    @Schema(description = "快照里的词条数（enabled=1 的词）")
    private Integer wordCount;

    @Schema(description = "快照版本号，每次成功 reload +1（含「加载成功但词是空的」那一轮）")
    private Integer version;

    @Schema(description = "当前这份快照的构建时间。★ 它就是这个实例「有多旧」的答案")
    private LocalDateTime loadedAt;

    @Schema(description = "最近一次 reload 的尝试时间（成功失败都算）")
    private LocalDateTime lastReloadAt;

    /** ★ 只放异常类名与截断后的第一行，完整栈在服务端日志里 —— 见 {@code SensitiveWordScanner} */
    @Schema(description = "最近一次加载失败的原因（只有类名与一行摘要），成功时为 null")
    private String lastError;

    @Schema(description = "最近一次加载失败的时间")
    private LocalDateTime lastErrorAt;

    /**
     * 库里 {@code enabled=1} 的<b>行数</b>。
     *
     * <p>★★ <b>它不是 {@link #wordCount} 的重复，两个数不相等正是本 VO 的重点。</b>
     * 不相等 ⇒ 有人改了库但还没 reload（或这个实例落后了）。
     */
    @Schema(description = "库里 enabled=1 的行数。★ 与 wordCount 不等 ⇒ 有改动还没 reload")
    private Integer enabledRows;

    /**
     * 库里的<b>总行数</b>（含停用的）。
     *
     * <p>★ 用途只有一个：管理员把一条词的 {@code enabled} 从 1 改成 0 之后，
     * {@link #enabledRows} 会减少而 {@link #totalRows} 不变 ——
     * 这是「我只停用了一条，没删掉它」的物证。
     * ★★ 两个数字并列，也让种子那 4 行 / 3 启用的基线<b>在界面上自解释</b>。
     */
    @Schema(description = "库里总行数（含停用的）")
    private Integer totalRows;

    /**
     * 查库取 {@link #enabledRows} / {@link #totalRows} 时失败了。
     *
     * <p>★★ <b>数据库连不上时这两个数是 {@code null} 而不是 0</b> ——
     * 这是本 VO 的又一处铁律 10：返回 0 会让页面显示「库里一条词都没有」，
     * 而真相是「读不到」。★ 前端据此显示「—」而不是「0」。
     */
    @Schema(description = "为 true 时 enabledRows/totalRows 为 null：不是 0 条，是【读不到】")
    private Boolean dbUnavailable;
}
