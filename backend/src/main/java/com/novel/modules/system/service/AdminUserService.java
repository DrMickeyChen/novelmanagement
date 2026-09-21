package com.novel.modules.system.service;

import com.novel.common.result.PageResult;
import com.novel.modules.system.vo.AdminUserVO;

import java.util.List;

/**
 * 管理端 —— 用户管理。
 *
 * <p><b>★★ 这一整个 Service 里有两个反复出现的东西，先看明白再读实现：</b>
 *
 * <h4>① 每一个「改用户」的方法末尾都有一句 {@code tokenStore.removeAll}</h4>
 * {@code JwtAuthenticationFilter} 的身份<b>每请求只从 Redis 读，不查数据库</b>
 * （这是 P1 的性能设计，见 {@code TokenStore} 的类注释）。
 * 所以<b>改了库里和身份有关的东西，必须把 Redis 里的登录态也清掉</b>，
 * 否则对方手中的旧 token 继续畅通无阻 —— 直到 7 天后自然过期。
 *
 * <pre>
 *   封禁        status = 0        → 必须踢（不踢 = 界面说封了、人还在用）
 *   改角色      sys_user_role     → 必须踢（不踢 = 对方界面看起来毫无变化）
 *   重置密码    password          → 必须踢（这个功能在安全事件里就是为了踢人）
 *   解封        status = 1        → ★ 不用踢（对方本来就没有会话）
 * </pre>
 * 漏掉任何一句都<b>不会报错</b>，只会让「管理员以为生效了、实际没有」。
 *
 * <h4>② 有两个方法会拒绝「管理员对自己动手」</h4>
 * 见 {@code ResultCode.USER_CANNOT_BAN_SELF} 的注释：
 * 封禁自己、或把自己身上的 ADMIN 拿掉，都会让管理员<b>再也进不来管理端</b>，
 * 而恢复只能进 MySQL 手工改。这和 D-6 的角色权限树自锁是同一类问题 ——
 * <b>UI 操作能造成不可逆损害</b>，所以宁可拒绝。
 *
 * @author MickeyChen
 */
public interface AdminUserService {

    /**
     * 用户分页。
     *
     * @param status   状态筛选：1 正常 / 0 已封禁。{@code null} 表示不限
     * @param roleCode 角色码筛选，如 {@code AUTHOR}。{@code null} 表示不限
     * @param keyword  用户名或昵称的模糊关键词。为空表示不限
     */
    PageResult<AdminUserVO> page(Integer status, String roleCode, String keyword, long page, long size);

    /**
     * 封禁 / 解封。
     *
     * @param status 1 解封，0 封禁
     */
    void changeStatus(Long userId, Integer status);

    /**
     * 覆盖式赋角色 —— <b>库里最终就是 {@code roleIds} 这几个，多一个不留</b>。
     *
     * @param roleIds 该用户最终应有的全部角色 id，<b>不能为空</b>（校验在 DTO 上）
     */
    void assignRoles(Long userId, List<Long> roleIds);

    /** 管理员重置某个用户的密码 */
    void resetPassword(Long userId, String newPassword);
}
