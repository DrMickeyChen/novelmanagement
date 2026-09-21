package com.novel.modules.system.service;

import com.novel.common.enums.AuditStatus;
import com.novel.common.result.PageResult;
import com.novel.modules.system.dto.AuthorApplyAuditDTO;
import com.novel.modules.system.dto.AuthorApplyCreateDTO;
import com.novel.modules.system.vo.AuthorApplyVO;

/**
 * 作者申请服务 —— 读者提交，管理员审核。
 *
 * <p><b>★★ 这个类是本模块唯一会和「角色」打交道的地方，而它的要害只有一句话：
 * 审核通过 = 改申请状态 + 往 {@code sys_user_role} 插一行，两件事必须在同一个事务里。</b>
 * <p>只改 {@code author_apply.status} 的话，申请人会看到「已通过」，
 * 但仍然进不去创作中心（{@code SecurityConfig} 的
 * {@code hasAnyRole("AUTHOR","ADMIN")} 会把他拦下）——
 * <b>这是本模块最典型的半成品 bug，而且不报错</b>。
 * {@code author_apply} 那张表看起来一切正常，查到最后才会发现
 * {@code sys_user_role} 里根本没那一行。
 *
 * <p><b>★ 三个写操作各自会踢谁下线：</b>
 * <pre>
 *   submit —— 不踢（只写了自己的申请，没改任何权限）
 *   audit 通过 —— 踢【申请人】（他的角色变了，不踢的话界面上毫无变化）
 *   audit 驳回 —— 不踢（角色没变）
 * </pre>
 * 判据是决定 6：<b>改了角色就必须踢</b>。
 *
 * @author MickeyChen
 */
public interface AuthorApplyService {

    /**
     * 管理端：分页查申请。
     *
     * @param status  申请状态，{@code null} = 全部。★ 最常用的恰恰是 {@code PENDING}
     * @param keyword 笔名、申请人用户名或昵称的模糊匹配，{@code null} = 不筛
     */
    PageResult<AuthorApplyVO> adminPage(AuditStatus status, String keyword, long page, long size);

    /**
     * 管理端：通过或驳回。
     *
     * <p>★ <b>重复提交同一份结果不报错</b>（幂等，见实现里的注释）；
     * 但把「已驳回」改成「已通过」会被拒 —— 终态之间没有边，要重来只能让申请人重新提交。
     *
     * @throws com.novel.common.exception.BusinessException
     *         {@code 1014} 申请不存在 / {@code 1016} 状态不允许 /
     *         {@code 1011} 系统里没有 AUTHOR 角色（种子数据被破坏）
     */
    void audit(Long applyId, AuthorApplyAuditDTO dto);

    /**
     * 读者端：提交申请。
     *
     * @return 新申请的 id。★ 前端拿到它并不需要做什么，返回是为了让
     *         「提交成功」这件事有一个可核对的对象（日志和自动化测试都用得到）
     * @throws com.novel.common.exception.BusinessException
     *         {@code 1015} 已经是作者了 / {@code 1009} 已有一份在审
     */
    Long submit(AuthorApplyCreateDTO dto);

    /**
     * 读者端：查自己最近的一份申请。
     *
     * <p><b>★ 返回 {@code null} 表示「从来没申请过」</b>，不是错误 ——
     * 前端据此决定显示「申请成为作者」的表单还是「审核中」的提示。
     *
     * <p><b>★★ 它不回答「我是不是作者」这个问题。</b>
     * 那个信息在 {@code /api/auth/me} 的 {@code roles} 里，前端也已经存在
     * {@code stores/user.js} 里了。硬塞进这里的话，这个 VO 就变成了
     * 「申请人 + 当前用户」两张表的混合体，而它的两个工厂方法
     * （{@code forAdmin} / {@code forReader}）也就不再对称。
     * <p>边界情况：管理员手工给人加了 AUTHOR、那个人从没提交过申请 ——
     * 这里返回 null（前端显示表单），提交时后端返回 {@code 1015}
     * 「你已经是作者了」。<b>这个结果是正确的</b>，不需要为此再加一个字段。
     *
     * <p>★ 「最近一份」而不是「全部」：这是<b>一条流水线</b>不是一张清单 ——
     * 被驳回之后重新提交会产生第二条记录，前端要显示的是最新那条的状态。
     * 全部历史在管理端的列表里能看到（那里按 userId 能查到所有份）。
     */
    AuthorApplyVO mine();
}
