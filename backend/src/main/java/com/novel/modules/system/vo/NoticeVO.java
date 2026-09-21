package com.novel.modules.system.vo;

import com.novel.modules.system.entity.SysNotice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告出参。<b>管理端和读者端共用一个类，走两个不同的工厂方法。</b>
 *
 * <p><b>★ 字段取舍：</b>
 * <pre>
 *   字段            管理端   读者端（首页横幅）
 *   ─────────────────────────────────────────
 *   id              有      有   （v-for 的 key）
 *   title           有      有   （横幅上显示的就是它）
 *   content         有      有   ← ★ 见下
 *   type            有      有   （公告 / 通知 的样式不同）
 *   publishTime     有      有   （「发布于 X」）
 *   status          有      【无】
 *   createTime      有      【无】
 *   updateTime      有      【无】
 * </pre>
 *
 * <p><b>★★ 读者端【也返回】{@code content}，这一条要想一下才明白：</b>
 * 直觉是「横幅只显示标题，正文点进去才看」—— 但公告<b>没有详情页</b>，
 * 它只有这一处展示。不返回正文，读者端的横幅上就只有一行标题、
 * 点开什么都没有，这条公告等于没发。
 * <p>所以横幅是<b>标题 + 正文</b>一起显示的（正文限高、超出折叠）。
 * 正文是 {@code TEXT}，一次最多 5 条、每条卡在 5000 字以内，
 * 最坏情况约 100KB —— 对一条首页横幅来说可以接受，
 * 而这个接口<b>不是</b>分页档案接口，见 {@code NoticeService#listPublished}。
 *
 * <p><b>★ 读者端不返回 {@code status}，理由和 {@code CommentVO} 不返回它
 * 正好相反、结论却一样：</b>
 * 评论的读者侧查询写死 {@code status = 1}，所以那一列恒等于 1；
 * 公告的读者侧查询同样写死 {@code status = 1}，同样恒等于 1。
 * <b>返回一个恒定的值，只会让前端误以为「可以根据它做判断」，
 * 从而写出永远为真的分支。</b>
 * <p>（对照 {@code AuthorApplyVO#forReader}：那个接口返回申请人<b>自己</b>
 * 的那一份，三种状态都真实存在，所以它<b>必须</b>返回 status。
 * 判据是「这个字段在读者侧是不是常量」，不是「它属于哪个模块」。）
 *
 * <p><b>★ 读者端也不返回 {@code createTime} / {@code updateTime}。</b>
 * 读者关心的是「什么时候<b>发布</b>的」，那是 {@code publishTime}；
 * 而 {@code updateTime} 会泄露「运营刚刚改过措辞」这类内部动作 ——
 * 读者看到公告时间跳了一下，除了困惑没有别的收获。
 * 管理端两个都要：{@code createTime} 是「这条草稿躺了多久」，
 * {@code updateTime} 是「最后是谁什么时候动的」。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "公告")
public class NoticeVO {

    @Schema(description = "公告 id")
    private Long id;

    @Schema(description = "标题", example = "系统维护通知")
    private String title;

    @Schema(description = "正文。★ 读者端也返回 —— 公告没有详情页，横幅是它唯一的展示位")
    private String content;

    /**
     * 类型：1 公告，2 通知。<b>以数字对外</b>。
     *
     * <p>★ <b>没有 {@code typeDesc}，这是有意的边界</b>：文案由后端给的
     * 只有那五个<b>有状态机</b>的 VO（Novel / Chapter / OutlineChapter /
     * Comment / AuthorApply）—— 它们的文案要和 {@code canTransitTo}
     * 的规则保持一致，写在两处迟早会漂。
     * <p>{@code type} 只是个 1/2 的开关，前端一行三元表达式的事，
     * 把它搬到后端只会让「改文案要重新打包后端」。
     * 项目里 {@code RoleVO} / {@code MenuVO} / {@code AdminUserVO} /
     * {@code CategoryVO} 的 status 全是这个处理，这里跟它们一队。
     */
    @Schema(description = "类型：1 公告，2 通知", example = "1")
    private Integer type;

    /** ★ 仅管理端。读者端为 null（JSON 里 key 消失）—— 见类注释 */
    @Schema(description = "状态：1 发布，0 下线。★ 仅管理端返回")
    private Integer status;

    @Schema(description = "发布时间。★ 草稿为 null，读者端只会看到非空的值")
    private LocalDateTime publishTime;

    @Schema(description = "创建时间。★ 仅管理端返回")
    private LocalDateTime createTime;

    @Schema(description = "最后修改时间。★ 仅管理端返回")
    private LocalDateTime updateTime;

    /**
     * 管理端视图。比读者端多 {@code status} / {@code createTime} / {@code updateTime}。
     *
     * <p>★ 这个方法不查库、不收补充参数 —— 公告的每一列都在本行里，
     * 没有像 {@code AuthorApplyVO#forAdmin} 那样需要批量补齐的关联字段。
     * 所以这里<b>没有 N+1 的风险</b>，逐行调用是安全的。
     */
    public static NoticeVO forAdmin(SysNotice entity) {
        if (entity == null) {
            return null;
        }
        NoticeVO vo = base(entity);
        vo.setStatus(entity.getStatus());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    /**
     * 读者端视图 —— <b>只给「已发布」的公告用</b>。
     *
     * <p><b>★★ 「只给已发布的」这件事不靠这个方法保证，靠接口保证：</b>
     * {@code GET /api/notices} 的查询条件里写死了 {@code status = 1}。
     * 这个方法<b>不做过滤</b> —— 它拿不到 scope，也没有「当前是哪个接口」的概念。
     * <p>所以：<b>任何用 {@code forReader} 返回未发布公告的代码都是漏洞</b>，
     * 而且它不会报错（返回的字段看起来一切正常，只是把一条还没发的公告
     * 提前泄露给了所有人）。
     * <p>这和 {@code CommentVO#forReader} / {@code AuthorApplyVO#forReader}
     * 的约定完全一致：<b>视图方法负责裁剪字段，不负责鉴权、不负责过滤。</b>
     */
    public static NoticeVO forReader(SysNotice entity) {
        return base(entity);
    }

    /**
     * 两端共有的字段。
     *
     * <p>★ 抽出来不只是省几行，而是让「哪些字段是共有的」只有一个答案：
     * 将来谁要给读者端加一个字段，改这里会同时影响两端，
     * 他会立刻意识到这是个需要判断的决定，而不是在两份复制粘贴里改了一份。
     */
    private static NoticeVO base(SysNotice entity) {
        NoticeVO vo = new NoticeVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setType(entity.getType());
        vo.setPublishTime(entity.getPublishTime());
        return vo;
    }
}
