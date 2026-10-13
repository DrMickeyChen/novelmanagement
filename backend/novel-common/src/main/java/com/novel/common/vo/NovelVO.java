package com.novel.common.vo;

import com.novel.common.enums.NovelStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 作品<b>列表</b>出参。读者端 / 作者端 / 管理端三处共用。
 *
 * <h3>★★ M4：这个类为什么从 modules/content 搬到了 novel-common</h3>
 *
 * <p>和 {@link NovelBriefVO} 在 M2 的搬家是<b>同一件事、同一个理由</b>：
 * M4 把搜索摘成独立服务 {@code novel-search} 之后，
 * search 要返回 {@code PageResult<NovelVO>}（<b>前端契约不许变</b>），
 * 而它<b>造不出这个类型</b> —— {@code NovelVO} 当时住在 content 的内部包里。
 *
 * <p>两条路，和 M2 时一模一样：
 * <ol>
 *   <li>search import content 的 VO —— 等于 search <b>依赖 content 的内部包</b>；</li>
 *   <li>搬进 novel-common 共用一份 —— 两边同一个类型，
 *       <b>Feign 序列化 / 反序列化天然对得上</b>。</li>
 * </ol>
 * 仍然选第二条。<b>判据也一样：这个类型是跨服务契约的一部分</b>
 * （它要在 search → content 的 HTTP 上传输）。
 *
 * <h3>★★★ 搬过来时【被拿掉】的东西：{@code of()} 和 {@code fillFrom()}</h3>
 *
 * <p>这两个方法原来都在这里，签名是 {@code of(Novel entity, String categoryName, String authorName)} ——
 * 收的是 content 的<b>表实体</b> {@code Novel}。
 * ★ 而 {@code Novel} <b>必须留在 content</b>（它带 {@code @TableName} 和逻辑删除字段，
 * 是那张表的形状）。把它一起搬进 common，common 就要依赖 content —— <b>方向就反了</b>。
 *
 * <p>★★ 所以装配逻辑搬到了 content 的 {@code com.novel.modules.content.vo.NovelVos}，
 * 而<b>字段清单仍然只有一处知道</b>（{@code NovelVos#fill} 里那 15 行具名 setter）——
 * 没有退化成「每个调用方各自 set 一遍」，那才是这次搬家最容易造成的损失。
 *
 * <p>★★ <b>为什么不照 {@link NovelBriefVO} 的样子改成「收展开的字段值」的
 * {@code of(Long id, String title, ...)}：</b>
 * 因为 {@code NovelVO} <b>有子类</b>（content 的 {@code NovelDetailVO extends NovelVO}），
 * 而子类的 {@code of()} 需要「把一个<b>已存在的实例</b>的父类字段填满」——
 * 静态工厂造不出这个效果，必须要一个<b>实例方法</b>。
 * ★ 更直接的理由：本类有 <b>15 个字段</b>，其中
 * {@code id/authorId/categoryId} 是三个相邻的 {@code Long}，
 * {@code chapterCount/wordCount/viewCount/isFinish} 是四个相邻的 {@code Integer} ——
 * 位置参数传错<b>编译期不报错、运行期静默填错字段</b>。
 * {@code NovelVos#fill} 用具名 setter 就是为了堵死这条。
 *
 * <h3>★★ 这个类里没有什么，以及为什么</h3>
 *
 * <p><b>★ 没有 {@code intro}（简介），这是刻意的，不要顺手加。</b>
 * 列表页一页 20 条，每条简介最多 1000 字 —— 加上去就是每页多传几十 KB，
 * 而列表页<b>一个字都不显示简介</b>。简介属于详情（content 的 {@code NovelDetailVO}）。
 * <p>「宁可多传一个字段」在这里不成立：这不是一个字段的事，是<b>每页 ×20</b> 的事，
 * 而且移动端（读者端将来要适配手机）流量是要花钱的。
 * ★★ 注意 M4 的「搜索加上简介」（决定 2）<b>不影响这一条</b>：
 * 那是把 {@code intro} 放进 <b>ES 索引</b>参与检索，
 * <b>不是</b>让它出现在列表的出参里。两件事不要混。
 *
 * <p><b>★ 同样没有 {@code auditRemark}（驳回理由）</b>：它是给作者看的一次性信息，
 * 属于详情。<b>尤其不能进读者端</b> —— 「驳回理由」是审核内部的话术
 * （「简介与正文不符，请修改后重新提交」），泄露给读者很荒唐。
 * 当然读者端按 C-7 约束 1 只会查到已上架的书，本来也不会有驳回理由，
 * 但依赖「数据恰好为空」不是防线，<b>不返回它才是防线</b>。
 *
 * <p><b>为什么 {@code categoryName} / {@code authorName} 放在 VO 里而不是前端自己查：</b>
 * 前端要拿它们得再发 N 个请求（N 本书 N 个作者），这就是经典的 N+1 问题搬到前端。
 * 后端一次 join 或者一次批量查昵称就解决了（批量查询见
 * {@code ContentUserLookup#nicknamesOf}，就是为了避开这个 N+1 才写的）。
 *
 * <p>★★ <b>M4 之后这两条路径的来源不一样了，值得记一笔：</b>
 * 正常搜索走 ES，命中后<b>只拿回一串 id</b>，再回 content 装配出本对象；
 * 而 {@code authorName} 在 content 那边仍然要靠 {@code ContentUserLookup} 去 auth 查。
 * 也就是说 <b>{@code search → content → auth} 是一条两次跨服务读的链</b>
 * （M4 之前是 {@code content → auth}，一次）—— 判断 9，如实记录，不粉饰。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作品列表项")
public class NovelVO {

    private String sourceType;
    private String originalAuthor;

    @Schema(description = "作品 id", example = "1")
    private Long id;

    @Schema(description = "书名", example = "测试书")
    private String title;

    @Schema(description = "封面 URL", example = "https://example.com/cover.jpg")
    private String cover;

    @Schema(description = "作者 id", example = "2")
    private Long authorId;

    /**
     * 作者昵称 —— 由 {@code ContentUserLookup} 去 {@code sys_user} 查出来的，
     * <b>不是 {@code novel} 表里的字段</b>。
     *
     * <p>★ 注意这里用的是<b>昵称</b>而不是 {@code username}：读者看到的是
     * 「测试作者」这种笔名，登录名（{@code author01}）是账号信息，
     * 对读者没有意义，而且它是对外可见的登录凭据的一半，能不暴露就不暴露。
     *
     * <p>★★ M4 起它<b>同时进了 ES 索引</b>（作为可检索字段，权重 {@code ^2}）——
     * 于是「作者改名」会让索引里的旧昵称陈旧，而 content <b>不知道</b> auth 改了名。
     * ★ 这是判断 8 那条约束的由来：<b>绝不新增 {@code auth → search} 的边</b>，
     * 陈旧靠<b>定时全量重建</b>收敛。
     */
    @Schema(description = "作者昵称", example = "测试作者")
    private String authorName;

    @Schema(description = "分类 id", example = "1")
    private Long categoryId;

    @Schema(description = "分类名称", example = "玄幻")
    private String categoryName;

    /**
     * 状态：0草稿 1待审核 2已上架 3已下架 4已驳回。
     *
     * <p>★ <b>以数字对外</b>（{@code NovelStatus.code} 上的 {@code @JsonValue} 决定），
     * 前端拿到的就是 {@code 2}。要回传给接口时也传数字 ——
     * 实测传 {@code "PUBLISHED"} 会得到 HTTP 400，详见 {@code NovelStatusDTO} 的类注释。
     */
    @Schema(description = "状态：0草稿 1待审核 2已上架 3已下架 4已驳回", example = "2")
    private NovelStatus status;

    /**
     * 状态的中文文案，如「已上架」。
     *
     * <p><b>★ 只用于显示，不要拿它做判断。</b>判断一律用上面的 {@code status} 数字 ——
     * 文案是会改的（「已上架」可能改成「连载中」），拿文案当条件的话，
     * 改一次文案就会静默地让某个分支永不命中。
     *
     * <p>给它是为了让三个前端不必各自维护一份 {@code 0→'草稿'} 的映射表
     * （那份映射表改起来要改三处，而且没人会记得）。
     */
    @Schema(description = "状态文案，★ 仅供显示", example = "已上架")
    private String statusDesc;

    /**
     * 已发布章节数。
     *
     * <p>★ 这是 {@code novel} 表上的<b>冗余字段</b>，由
     * {@code NovelServiceImpl#syncNovelStat} 重算维护，
     * 只在章节发布状态变化时刷新。<b>不要在这里现算</b> ——
     * 列表 20 条就是 20 次 {@code COUNT}，而且算出来的值可能和详情页不一致。
     *
     * <p>口径是「<b>已发布</b>章节数」，草稿章不算 ——
     * 作者在作者端看到的也是这个数，所以作者不会把草稿章算进「我写了 10 章」。
     */
    @Schema(description = "已发布章节数", example = "12")
    private Integer chapterCount;

    @Schema(description = "已发布章节总字数", example = "34567")
    private Integer wordCount;

    /** 阅读量。★ P2 恒为 0，P3 做阅读器时才有值 */
    @Schema(description = "阅读量。P2 尚未统计，恒为 0", example = "0")
    private Integer viewCount;

    @Schema(description = "是否完结：0 连载，1 完结", example = "0")
    private Integer isFinish;

    /**
     * 评分（书评平均分，1 位小数）。<b>冗余列，由 {@code novel_comment} 重算而来。</b>
     *
     * <p><b>★ {@code 0} 的意思是「还没有人评分」，不是「0 分」。</b>
     * 列是 {@code NOT NULL DEFAULT 0.0}，所以没有第二个值可以表达「无」——
     * 前端必须写 {@code score > 0 ? 显示星星 : 显示「暂无评分」}，
     * <b>不要直接渲染 0 星</b>：那是在说「这本书被评了 0 分」，
     * 而 0 分是打不出来的（{@code CommentCreateDTO} 的评分最小是 1）。
     *
     * <p>★ <b>不在 {@code NovelBriefVO} 里</b>：列表页（首页/榜单/搜索）
     * 不显示评分，给它加一列只会让那几条查询多算一个用不上的值。
     * 详情页要，所以放在这一层 —— 它是 {@code NovelDetailVO} 的父类。
     */
    @Schema(description = "评分（书评平均分）。0 表示暂无评分", example = "4.3")
    private BigDecimal score;

    /**
     * 最后更新时间 —— 取的是<b>最新一个已发布章节</b>的发布时间，
     * 不是 {@code novel.update_time}（那是「这一行记录最后被改的时间」，
     * 管理端改个分类也会变，拿它排「最近更新」会排出一堆假更新）。
     *
     * <p>读者端按它倒序排，所以它可能为 {@code null}：
     * 一本还没发布过任何章节的书没有「最后更新时间」。
     * 由于全局配了 {@code default-property-inclusion: non_null}，
     * <b>为 null 时这个字段在 JSON 里直接不出现</b>，前端取值要写 {@code ?? ''}。
     *
     * <p>★★ <b>M4 起它同时是 ES 的排序字段。</b>上面那条「可能为 null」
     * 在 ES 里会变成一个具体的坑：{@code null} 的文档在 {@code desc} 排序里
     * 默认排最后（ES 的默认就是 {@code _last}）—— 这正是我们要的。
     * ★ 但<b>别把 {@code missing} 写进索引的 mapping</b>：
     * 它不是 mapping 参数，写进去建索引直接 400（见 {@code IndexService} 的长注释）。
     */
    @Schema(description = "最后更新时间（最新已发布章节的时间），可能为空", example = "2026-09-20 10:30:00")
    private LocalDateTime lastUpdateTime;
}
