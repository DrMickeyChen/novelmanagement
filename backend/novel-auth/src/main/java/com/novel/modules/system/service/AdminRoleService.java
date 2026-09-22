package com.novel.modules.system.service;

import com.novel.modules.system.dto.RoleSaveDTO;
import com.novel.modules.system.vo.RoleVO;

import java.util.List;

/**
 * 管理端 —— 角色管理。
 *
 * <p><b>★ S6 阶段只有「列表」一个方法</b>，因为用户管理页的「编辑角色」弹窗
 * 需要一份角色清单来渲染多选框，没有它前端就只能把
 * {@code ADMIN/AUTHOR/READER} 写死在代码里 —— 那样管理员新建的角色
 * 永远选不到，而且不报错。S13 补齐了增删改和「给角色赋菜单」，
 * <b>原来的 list() 一行没动</b>（这正是 S6 把它单独建出来的目的）。
 *
 * <p><b>★★ 这个接口有三条不变式，实现类里逐条都有展开的注释：</b>
 * <ol>
 *   <li><b>内置角色（ADMIN / AUTHOR / READER）不可删、不可停用、不可改标识。</b>
 *       任何一条被破坏都会造成一次全局性故障，且不可逆 —— 见 {@code ResultCode.ROLE_BUILTIN}。</li>
 *   <li><b>不能去掉自己所属角色上的「角色管理 / 菜单管理」权限。</b>
 *       去掉之后左侧菜单里那两项立刻消失，而界面上再也没有地方能改回来，
 *       只能进 MySQL 手工 INSERT —— 见 {@code ResultCode.ROLE_SELF_MENU_LOCK}。</li>
 *   <li><b>角色下还有用户时不允许删除。</b>留下悬空 {@code role_id} 会让那些用户
 *       变成「登录成功但什么都进不去」，且界面上看不出原因。</li>
 * </ol>
 *
 * <p><b>★ 改完角色必须把相关用户踢下线</b>（{@code TokenStore.removeAll}）。
 * 角色和权限是<b>登录时</b>写进 Redis 的，之后每请求只读 Redis ——
 * 不踢的话，被改的人界面看起来毫无变化，管理员会以为没保存成功。
 *
 * @author MickeyChen
 */
public interface AdminRoleService {

    /** 全部角色（不分页，含已停用），带用户数。★ 用户管理页的「编辑角色」弹窗也用这个 */
    List<RoleVO> list();

    /**
     * 新建角色。
     *
     * @return 新角色的 id
     * @throws com.novel.common.exception.BusinessException {@code ROLE_CODE_EXISTS(1017)} 角色标识重复
     */
    Long create(RoleSaveDTO dto);

    /**
     * 编辑角色（角色码 / 角色名 / 备注 / 状态，全部覆盖式）。
     *
     * <p>★ 会把这个角色下的所有用户踢下线（角色码和状态都会影响
     * 登录时算出来的那份身份快照）。
     *
     * @throws com.novel.common.exception.BusinessException
     *         {@code ROLE_NOT_FOUND(1011)} / {@code ROLE_CODE_EXISTS(1017)} /
     *         {@code ROLE_BUILTIN(1018)}（改内置角色的标识或状态）
     */
    void update(Long id, RoleSaveDTO dto);

    /**
     * 删除角色（逻辑删除）。
     *
     * <p>★ 会级联清掉这个角色的 {@code sys_role_menu} 关联行
     * （那是物理删 —— 那张表没有 {@code deleted} 列）。
     *
     * @throws com.novel.common.exception.BusinessException
     *         {@code ROLE_NOT_FOUND(1011)} / {@code ROLE_BUILTIN(1018)} /
     *         {@code ROLE_IN_USE(1019)} 角色下还有用户
     */
    void delete(Long id);

    /** 某个角色已勾选的菜单 id 列表。★ 给前端的权限树做回显 */
    List<Long> menuIdsOf(Long roleId);

    /**
     * 给角色赋菜单（覆盖式：先物理全删，再批量插入）。
     *
     * @return <b>被强制下线的用户数</b>（不是登录态条数）——
     *         前端要拿它写「已保存，N 名用户需要重新登录」。
     *         ★ 0 是完全正常的结果：这个角色下没有任何人在线
     * @throws com.novel.common.exception.BusinessException
     *         {@code ROLE_NOT_FOUND(1011)} / {@code MENU_NOT_FOUND(1021)} /
     *         {@code ROLE_SELF_MENU_LOCK(1020)} 会把自己锁死
     */
    int assignMenus(Long roleId, List<Long> menuIds);
}
