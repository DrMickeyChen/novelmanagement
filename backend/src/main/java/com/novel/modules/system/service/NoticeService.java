package com.novel.modules.system.service;

import com.novel.common.result.PageResult;
import com.novel.modules.system.dto.NoticeSaveDTO;
import com.novel.modules.system.vo.NoticeVO;

import java.util.List;

/**
 * 公告服务 —— 管理员发布，读者端首页横幅展示。
 *
 * <p><b>★★ 本服务最要害的一条规则：{@code status = 1} 当且仅当
 * {@code publishTime} 非空，而 {@code publishTime} 由后端在「状态发生变化」
 * 的那一刻盖上去，前端碰不到它。</b>
 * <pre>
 *   新建为草稿（0）        → publish_time 保持 NULL
 *   新建即发布（1）        → 盖当前时间
 *   草稿 → 发布            → 盖当前时间
 *   发布 → 下线            → 【保留】原时间，不清空
 *   下线 → 再发布          → 【重新】盖当前时间（公告重新置顶）
 *   编辑但不动 status      → 【不动】原时间
 * </pre>
 * 六种情况全部由 {@code NoticeServiceImpl#resolvePublishTime} 一处负责。
 * 它值得单独成一个方法，是因为这里每一条都对应一个具体的坏结果：
 * 时间戳置空 → 读者端排序出现 NULL，那条公告永远沉底；
 * 编辑就刷新时间 → 改个错别字把一条三周前的公告顶到首页最上面。
 *
 * <p><b>★★ 本服务【不写】{@code audit_log}，这是有意的。</b>
 * 审核流水记的是「有人提交了东西、别人裁决了它」——作品、章节、评论、作者申请。
 * 公告是运营内容：没有申请人、没有待审态、没有驳回理由，
 * 管理员发布它就是它自己的终态。给它补一条流水，只会让审核日志页上
 * 多出一类既没有 targetTitle 也没有审核人的记录（见
 * {@code ContentUserLookup} 里那个补标题的 switch，它根本没有公告这一支）。
 *
 * <p><b>★ 没有 {@code @Transactional} 的地方</b>：本服务的每个写操作都只动
 * {@code sys_notice} 一张表、一条语句，没有「两件事必须一起成功」的场景 ——
 * 对照 {@code AuthorApplyService#audit}（申请状态 + 角色 + 流水，三件事）。
 * 单语句本身就是原子的，加事务注解只会让人以为这里有跨表的一致性要担心。
 *
 * @author MickeyChen
 */
public interface NoticeService {

    /**
     * 管理端：分页查公告。
     *
     * @param status  状态，{@code null} = 全部。★ 传一个不存在的值（比如 9）
     *                只会得到空列表而<b>不报错</b> —— 和作品 / 评论 / 用户列表
     *                的处理一致（那几个也是 {@code Integer}）。
     *                这里不像作者申请那样用枚举，是因为公告状态压根没有枚举，
     *                见 {@code SysNotice} 的类注释
     * @param keyword 标题的模糊匹配，{@code null} = 不筛
     */
    PageResult<NoticeVO> adminPage(Integer status, String keyword, long page, long size);

    /**
     * 管理端：新增公告。
     *
     * <p>★ 新增时可以直接是「已发布」（{@code status = 1}），
     * 这在真实使用里是最常见的一种 —— 运营写一句话就想马上发出去，
     * 不该逼他先存草稿再点一次发布。
     *
     * @return 新公告的 id
     */
    Long create(NoticeSaveDTO dto);

    /**
     * 管理端：编辑公告，<b>同时承担「发布 / 下线」两个动作</b>。
     *
     * <p>★ 前端的行内「发布 / 下线」按钮走的就是这个方法（把 {@code status} 翻一下、
     * 其余字段原样带上），和分类管理页的「启用 / 停用」是同一套做法。
     * 公告没有状态机，不需要单独的 {@code /publish} 接口。
     *
     * @throws com.novel.common.exception.BusinessException {@code 7001} 公告不存在
     */
    void update(Long id, NoticeSaveDTO dto);

    /**
     * 管理端：删除公告（逻辑删除）。
     *
     * <p>★ 已发布的公告也可以删 —— 删掉之后读者端立刻看不到它。
     * <b>「只想让它暂时不出现」应该用下线，不是删除</b>，
     * 这个区别由前端的二次确认弹窗讲清楚（见 {@code admin-web} 的公告管理页）。
     *
     * <p><b>★ 删一条已经不存在的公告会返回 {@code 7001}，不会静默成功。</b>
     * 触发它的真实场景是「别人先删了、你的页面还是旧的」——
     * 此时如实说「公告不存在」比返一个成功更有用：它告诉你
     * 「你看到的列表不是最新的」，而不是让你以为刚才那一击生效了。
     * <p>（唯一的例外是几毫秒的并发窗口：查到之后、删之前被别人删掉，
     * 那一次返回成功 —— 见实现里的注释。两种处理回答的是两个不同的问题。）
     *
     * @throws com.novel.common.exception.BusinessException {@code 7001} 公告不存在
     */
    void delete(Long id);

    /**
     * 读者端：已发布的公告，<b>按发布时间倒序</b>。
     *
     * <p><b>★★ 这不是一个分页接口，是有意的。</b>
     * 它的消费者只有一个：读者端首页顶上那条横幅。横幅要的是「最新几条」，
     * 不是一份可以翻页的档案 —— 所以 query 参数是 {@code limit} 而不是
     * {@code page/size}，并且有硬上限（见实现里的常量）。
     * <p>做成分页的后果是：前端要维护 page/size 两个状态，
     * 而横幅<b>永远只会渲染前几条</b>，第 2 页的数据请求出来了也没人看。
     *
     * <p>★ <b>只返回 {@code status = 1} 且 {@code deleted = 0} 的。</b>
     * 未发布的公告提前泄露给所有人是这条查询唯一可能犯的错，
     * 而它不会报错 —— 见 {@code NoticeVO#forReader} 的注释。
     *
     * @param limit 最多返回几条，会被夹到 1~20（默认 5）
     */
    List<NoticeVO> listPublished(int limit);
}
