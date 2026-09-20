package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.SysUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户表 Mapper。
 *
 * <p><b>继承 {@code BaseMapper<SysUser>} 就白送了 18 个方法</b>
 * （insert / deleteById / updateById / selectById / selectList / selectPage ...），
 * 基础的增删改查一行 SQL 都不用写。
 *
 * <p>只有「多表关联」和「复杂条件查询」才写自定义方法 ——
 * 简单的单表条件用 {@code LambdaQueryWrapper} 在 Service 层拼就行。
 *
 * <p><b>★ 关于下面那几个涉及 {@code sys_role} / {@code sys_user_role} 的方法：</b>
 * 它们严格来说不属于「用户表」，按分层应该各自建
 * {@code SysRoleMapper} / {@code SysUserRoleMapper}。
 * P1 阶段只有登录时用到这几条查询，为它们单独建两个 Mapper 接口 +
 * 两个实体类要 4 个文件，收益不大。<b>等 P2 做角色管理界面时再拆出去</b>，
 * 那时这几个方法原样搬走即可。
 *
 * @author MickeyChen
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 根据用户名查用户（用于登录）。
     *
     * <p>这里刻意手写 SQL 而没有用 {@code LambdaQueryWrapper}，是为了演示
     * <b>「用 @Select 注解写简单 SQL」</b> 这个中间档 ——
     * 比 Wrapper 直观，又不用为了一句话建一个 XML 文件。
     * 更复杂的（比如三表 join）再放到 {@code resources/mapper/xxx.xml} 里。
     *
     * <p>注意 {@code deleted = 0} 这个条件手写出来了：
     * {@code @TableLogic} 的自动过滤<b>只对 MyBatis-Plus 自己生成的方法生效</b>，
     * 手写 SQL 时必须自己带上，否则会把已注销的用户也查出来。
     */
    @Select("""
            SELECT id, username, password, nickname, avatar, email, phone,
                   status, create_time, update_time
            FROM sys_user
            WHERE username = #{username}
              AND deleted = 0
            """)
    SysUser selectByUsername(@Param("username") String username);

    /* ==================== 角色 / 权限（登录时用） ==================== */

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

    /* ==================== 注册时用 ==================== */

    /**
     * 按角色码查角色 ID，例：{@code selectRoleIdByCode("READER")}。
     *
     * <p><b>★ 为什么按码查而不是在代码里写死 {@code role_id = 1}：</b>
     * 自增 ID 是「数据库的实现细节」，换一套环境、重新灌一次种子数据
     * 就可能变（比如先插了别的角色）。而 {@code role_code} 上有唯一索引
     * （{@code uk_role_code}），是业务上的稳定标识。写死数字的代码
     * 在本地跑得好好的，到了别人机器上就给用户绑了个管理员角色。
     *
     * @return 角色 ID；角色不存在时返回 {@code null}（调用方要判空）
     */
    @Select("""
            SELECT id
            FROM sys_role
            WHERE role_code = #{roleCode}
              AND deleted = 0
            """)
    Long selectRoleIdByCode(@Param("roleCode") String roleCode);

    /**
     * 给用户绑定一个角色（注册时授予默认的 READER）。
     *
     * <p>{@code sys_user_role} 上有唯一索引 {@code uk_user_role(user_id, role_id)}，
     * 所以重复绑定会抛唯一键冲突 —— 这其实是好事，
     * 它保证了「一个用户不会有两条相同的角色记录」。
     */
    @Insert("""
            INSERT INTO sys_user_role (user_id, role_id)
            VALUES (#{userId}, #{roleId})
            """)
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
