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
}
