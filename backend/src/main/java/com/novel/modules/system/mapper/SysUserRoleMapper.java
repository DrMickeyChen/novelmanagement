package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.SysUserRole;
import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户-角色关联 Mapper，对应 {@code sys_user_role}。
 *
 * <p>这几个方法原本写在 {@code SysUserMapper} 里（P1 只有登录用得到，
 * 为它们单开一个 Mapper 不划算，那边的注释当时就写明了「等做角色管理时再拆出去」）。
 * P5 做用户管理和角色管理，正好是那个时候 —— 现在原样搬到了这里。
 * <b>搬动只改了归属，SQL 一行没动。</b>
 *
 * <p><b>★ 为什么 {@code selectPermsByUserId}（查权限点）也在这里：</b>
 * 它的主语是「某个用户 <b>的角色</b>」—— SQL 的起点表就是 {@code sys_user_role}，
 * 后面三跳（role → role_menu → menu）只是把它展开。
 * 换个说法：这个 Mapper 管的是<b>「关联」这件事</b>，
 * 凡是「从关联出发去查别的表」都归它，而单查 {@code sys_role} 自己的行
 * 归 {@code SysRoleMapper}。
 *
 * @author MickeyChen
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 查一个用户的所有角色码，如 {@code ["ADMIN"]}。
     *
     * <p><b>★ 两个 {@code deleted = 0} 分别属于谁，别搞混：</b>
     * <pre>
     *   r.deleted = 0  → sys_role 表【有】deleted 列，必须自己写
     *   ur 表          → sys_user_role 【没有】deleted 列，不能写
     * </pre>
     * 这一点很容易写错：给没有这列的表加上 {@code deleted = 0} 会直接
     * {@code Unknown column} 报错；反过来漏了 {@code r.deleted = 0}，
     * 已删除的角色还会继续生效。
     *
     * <p>同时过滤 {@code r.status = 1}：停用某个角色（比如临时关闭作者权限）
     * 时，用户不该再凭它通过鉴权。
     *
     * <p>结果是 {@code List} 而不是单个字符串 —— {@code sys_user_role}
     * 是多对多，一个用户确实可能同时是 AUTHOR 和 ADMIN。
     */
    @Select("""
            SELECT r.role_code
            FROM sys_user_role ur
                     JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.deleted = 0
              AND r.status = 1
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 查一个用户拥有的全部权限码，如 {@code ["content:novel:list", "system:user:list"]}。
     *
     * <p>路径是「用户 → 角色 → 菜单/权限点」三跳 join，这正是
     * RBAC 的典型查询形态。
     *
     * <p>三个 {@code deleted = 0}：{@code sys_role} 和 {@code sys_menu}
     * 都有 {@code deleted} 列，{@code sys_role_menu} 没有。
     *
     * <p>{@code DISTINCT} 不能省：两个角色可能被授予了同一个权限点
     * （比如 ADMIN 和 AUTHOR 都有 {@code content:novel:list}），
     * 不去重的话权限列表里会出现重复项，虽然不报错，
     * 但会让「这个人到底有哪些权限」变得没法直接看。
     *
     * <p>{@code perms IS NOT NULL} 是因为 {@code sys_menu} 里
     * 目录行（type=1）本身没有权限码，它们的 perms 是空的。
     */
    @Select("""
            SELECT DISTINCT m.perms
            FROM sys_user_role ur
                     JOIN sys_role r ON r.id = ur.role_id
                     JOIN sys_role_menu rm ON rm.role_id = r.id
                     JOIN sys_menu m ON m.id = rm.menu_id
            WHERE ur.user_id = #{userId}
              AND r.deleted = 0
              AND r.status = 1
              AND m.deleted = 0
              AND m.status = 1
              AND m.perms IS NOT NULL
              AND m.perms <> ''
            """)
    List<String> selectPermsByUserId(@Param("userId") Long userId);

    /**
     * 查一个用户已拥有的角色 <b>id</b> 列表。
     *
     * <p><b>★ 和 {@code selectRoleCodesByUserId} 是两个用途，别互相替代：</b>
     * <ul>
     *   <li>角色<b>码</b>（ADMIN）→ 写进 Redis、鉴权用</li>
     *   <li>角色 <b>id</b> → 给「编辑某人的角色」那个界面做回显用。
     *       回显必须是 id：界面拿到的是角色下拉框的 value，
     *       而多选框要的是「哪些被选中了」，拿一串字符串码还得反查一遍</li>
     * </ul>
     *
     * <p><b>★ 这里【没有】{@code r.status = 1}，和上面那个方法刻意不一致：</b>
     * 上面是鉴权（停用的角色不该生效），这里是回显（某个角色虽然停用了，
     * 但这个人确实还挂着它，界面上要如实显示出来，否则管理员一保存
     * 就会把这个关联悄悄删掉）。
     */
    @Select("""
            SELECT ur.role_id
            FROM sys_user_role ur
                     JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.deleted = 0
            """)
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 给用户绑定一个角色（注册时授予默认的 READER）。
     *
     * <p>{@code sys_user_role} 上有唯一索引 {@code uk_user_role(user_id, role_id)}，
     * 所以重复绑定会抛唯一键冲突 —— 这其实是好事，
     * 它保证了「一个用户不会有两条相同的角色记录」。
     *
     * <p><b>★ 但调用方不能就此假设「一次就成功」：</b>重复授权是真实存在的场景
     * （管理员手工加过 AUTHOR，后来又批了一份作者申请），
     * 那时这里会抛 {@code DuplicateKeyException} → 500。
     * 需要的语义是「已经有了就算了」，所以调用方要捕获它，
     * 或者改用 {@code INSERT IGNORE}。见 {@code AuthorApplyServiceImpl}。
     */
    @Insert("""
            INSERT INTO sys_user_role (user_id, role_id)
            VALUES (#{userId}, #{roleId})
            """)
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /**
     * 查挂着某个角色的全部用户 id —— 用于「改完角色把这些人踢下线」（S13）。
     *
     * <p><b>★ 为什么改菜单也要踢人：</b>角色/权限是在<b>登录时</b>从库里
     * 查出来写进 Redis 的（{@code AuthServiceImpl}），之后每请求都只读 Redis。
     * 管理员改完菜单不减 {@code tokenStore.removeAll} 的话，
     * 被改的人<b>界面看起来毫无变化</b>，管理员会以为没保存成功，然后再改一次。
     * 这和 {@code AdminUserServiceImpl#assignRoles} 里那句 removeAll
     * 是同一条理由。
     *
     * <p><b>★ 只返回 id，不返回 {@code SysUser}：</b>踢人只需要 id
     * （{@code removeAll} 的入参就是 userId）。查整行是白读 20 个字段，
     * 而且会把「sys_user 长什么样」这件事泄漏到这个关联 Mapper 里 ——
     * 它管的是「关联」，不是用户档案。
     */
    @Select("""
            SELECT user_id
            FROM sys_user_role
            WHERE role_id = #{roleId}
            """)
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 每个角色各挂了多少用户 —— 给角色列表显示「用户数」用。
     *
     * <p><b>★ 为什么是「一次分组查」而不是「每个角色 count 一次」：</b>
     * 后者是 N+1（角色列表有 8 个角色就是 8 次查询）。角色是个位数量级，
     * 8 次查询不会让页面卡，但它是那种「写的时候顺手、量上来之后才疼」
     * 的写法 —— {@code AdminUserServiceImpl#toVOList} 上已经为同类问题
     * 写过一大段注释，这里保持同一个做法。
     *
     * <p><b>★ 返回 {@code RoleUserCount} 而不是 {@code Map<String, Object>}：</b>
     * 同 {@code DashboardMapper} 类注释里那条 —— Map 取值靠字符串 key，
     * 而列名大小写取决于驱动，{@code get("userCount")} 拿到 null 之后是 NPE，
     * 在 Windows 上还经常蒙混过关。走「列别名 → 属性名」的映射稳得多。
     *
     * <p>★ 不返回「用户数为 0 的角色」——{@code GROUP BY} 造不出空组的行，
     * 缺的由调用方补 0。这不是缺陷：和 {@code DashboardMapper} 的状态分布
     * 是同一个处理方式（那边由 {@code NovelStatus.values()} 补，
     * 这边由角色列表本身补）。
     */
    @Select("""
            SELECT role_id AS roleId, COUNT(*) AS userCount
            FROM sys_user_role
            GROUP BY role_id
            """)
    List<RoleUserCount> countGroupByRoleId();

    /** 「某个角色下有几个用户」。字段名是上面的 SQL 别名，用 Map 的理由见上 */
    @Data
    class RoleUserCount {
        private Long roleId;
        private Long userCount;
    }

    /**
     * 删掉一个用户的全部角色关联（覆盖式赋角色的第一步）。
     *
     * <p><b>★★ 这是【物理 DELETE】，不是逻辑删除，而且必须如此。</b>
     * {@code sys_user_role} 表<b>没有 {@code deleted} 列</b>。
     * 如果哪天有人「顺手」给 {@code SysUserRole} 加上 {@code @TableLogic}，
     * 这里会直接 {@code Unknown column 'deleted'} 报错；
     * 而如果加的是列而不是注解，那么「删了再给」会撞
     * {@code uk_user_role} 唯一键 —— 那条被逻辑删掉的行还在索引里。
     * <b>这张表的设计就是「关联不存在 = 行不存在」，不要改。</b>
     *
     * <p><b>★ 调用方必须包在事务里，并且紧跟着 {@code insertUserRole}。</b>
     * 单独调它 = 把这个人变成「什么角色都没有」的状态 ——
     * 登录后 roles 是空数组，前端拿不到角色，
     * 表现是「登录成功但什么页面都进不去」（见 {@code AuthServiceImpl}
     * 注册方法上那段关于「没有角色的用户」的注释）。
     *
     * @return 实际删除的行数。★ 可能是 0（本来就没有角色），不是错误
     */
    @Delete("""
            DELETE FROM sys_user_role
            WHERE user_id = #{userId}
            """)
    int deleteUserRoles(@Param("userId") Long userId);
}
