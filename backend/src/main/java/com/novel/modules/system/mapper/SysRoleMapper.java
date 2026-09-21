package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色表 Mapper，对应 {@code sys_role}。
 *
 * <p>继承 {@code BaseMapper<SysRole>} 之后，角色的增删改查
 * （{@code selectList} / {@code insert} / {@code updateById} / {@code deleteById} …）
 * 一行 SQL 都不用写，而且<b>自动带 {@code deleted = 0}</b>——
 * 这正是 {@code @TableLogic} 的价值。
 *
 * <p><b>★ 只有「从关联出发」的查询不在这个类里</b>
 * （比如「某用户有哪些角色」），它们在 {@code SysUserRoleMapper}。
 * 分界线是 <b>SQL 的起点表</b>：起点是 {@code sys_role} 的归这里，
 * 起点是 {@code sys_user_role} 的归那边。
 *
 * @author MickeyChen
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 按角色码查角色 ID，例：{@code selectRoleIdByCode("READER")}。
     *
     * <p><b>★ 为什么按码查而不是在代码里写死 {@code role_id = 1}：</b>
     * 自增 ID 是「数据库的实现细节」，换一套环境、重新灌一次种子数据
     * 就可能变（比如先插了别的角色）。而 {@code role_code} 上有唯一索引
     * （{@code uk_role_code}），是业务上的稳定标识。写死数字的代码
     * 在本地跑得好好的，到了别人机器上就给用户绑了个管理员角色。
     *
     * <p><b>★ 注意这个方法【没有】过滤 {@code status}：</b>
     * 它是给「写关联」用的（注册时授予 READER、审批作者申请时授予 AUTHOR），
     * 不是鉴权。一个被停用的角色仍然应该能被授予 ——
     * 停用是「暂时不生效」，不是「这个角色不存在」。
     * 真正要挡的是 {@code selectRoleCodesByUserId} 那一条（它带 status = 1）。
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
}
