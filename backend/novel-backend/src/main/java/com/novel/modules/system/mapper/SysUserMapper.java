package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
 * <p><b>★★ 这里曾经有 4 个涉及 {@code sys_role} / {@code sys_user_role} 的方法，
 * P5 已经搬到别处了 —— 如果你在找它们，看这里：</b>
 * <pre>
 *   selectRoleCodesByUserId  ┐
 *   selectPermsByUserId      ├─ → SysUserRoleMapper   （起点表是 sys_user_role）
 *   insertUserRole           │
 *   deleteUserRoles          ┘
 *   selectRoleIdByCode         → SysRoleMapper        （起点表是 sys_role）
 * </pre>
 * P1 只有登录用得到这几条查询，为它们单独建两个 Mapper + 两个实体要 4 个文件，
 * 不划算 —— 当时的注释写的是「等做角色管理界面时再拆出去，原样搬走即可」。
 * P5 做用户管理和角色管理正好是那个时候，<b>搬动只改了归属，SQL 一行没动。</b>
 *
 * <p>分界线是 <b>SQL 的起点表</b>：
 * 起点是 {@code sys_user} 的留在这里，起点是 {@code sys_user_role} /
 * {@code sys_role} 的去了各自的 Mapper。
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

    /*
     * ★ 角色 / 权限相关的 4 个方法（selectRoleCodesByUserId / selectPermsByUserId /
     *   insertUserRole / deleteUserRoles）P5 已搬到 SysUserRoleMapper，
     *   selectRoleIdByCode 搬到了 SysRoleMapper。
     *   原因和映射关系见类注释里的那张表 —— 这里只留一个空位，别再补回来。
     */

    /* ==================== 下面留给「起点表是 sys_user」的查询 ==================== */
}