package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 角色-菜单关联 Mapper，对应 {@code sys_role_menu}。
 *
 * <p><b>★★ 这个类的每个方法都必须是【手写 SQL】，原因和 {@code SysMenuMapper}
 * 正好相反：{@code sys_role_menu} 没有 {@code deleted} 列。</b>
 * MP 自动生成的 SQL 会带上 {@code deleted = 0}（因为实体上挂了
 * {@code @TableLogic} 才会带；这里实体刻意没挂，所以自动 SQL 反而是干净的）。
 * <b>真正的理由是「覆盖式赋权」这个操作本身没有对应的 MP 方法</b> ——
 * 「按 role_id 全删」和「批量插入」都得自己写，写的时候顺手把
 * 「这张表没有 deleted、这里是物理删」这件事写在注释里，
 * 免得将来有人给它补上 {@code @TableLogic}。
 *
 * <p><b>★ 分界线（和 {@code SysUserRoleMapper} 同一条）：</b>
 * SQL 的<b>起点表</b>是 {@code sys_role_menu} 的归这里；
 * 起点是 {@code sys_user_role} 的归那边。所以
 * 「这个角色被授了哪些菜单」在这里，
 * 「哪些用户挂了这个角色」在 {@code SysUserRoleMapper}。
 *
 * @author MickeyChen
 */
@Mapper
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {

    /**
     * 查一个角色已勾选的菜单 id 列表 —— 给前端 {@code el-tree} 做回显。
     *
     * <p>★ 返回的是<b>原始关联行</b>，不做任何过滤：如果某个 menuId 指向的菜单
     * 已经被逻辑删除了，它仍然会出现在这里。这不会造成问题（前端的树里
     * 本来就没有那个节点，多出来的 key 会被忽略），但<b>不能</b>反过来
     * 「顺手 join sys_menu 过滤一下」—— 那会让下一次保存时，
     * 那条关联被静默丢掉（前端没勾它，因为树里根本没有它）。
     */
    @Select("""
            SELECT menu_id
            FROM sys_role_menu
            WHERE role_id = #{roleId}
            """)
    List<Long> selectMenuIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量插入角色的菜单关联。
     *
     * <p><b>★ 调用方必须保证 {@code menuIds} 非空</b>：空集合会让
     * {@code foreach} 生成 {@code VALUES } —— 一个语法错误。
     * 而且语义上也讲不通：一个菜单都不给的场景应该<b>直接不插</b>，
     * 而不是插一条「空行」。
     *
     * <p>这里<b>不</b>需要用 {@code INSERT IGNORE}：调用它的地方
     * 第一步就是 {@code deleteByRoleId}（同一个事务里），
     * 表已经被清空了，撞不上 {@code uk_role_menu}。
     * 这和 {@code insertUserRole} 的情况不同 —— 那个是「追加式」的，
     * 真的可能撞键。
     */
    @Insert("""
            <script>
            INSERT INTO sys_role_menu (role_id, menu_id) VALUES
            <foreach collection="menuIds" item="menuId" separator=",">
                (#{roleId}, #{menuId})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("roleId") Long roleId, @Param("menuIds") Collection<Long> menuIds);

    /**
     * 删掉一个角色的全部菜单关联（覆盖式赋权的第一步）。
     *
     * <p><b>★★ 这是【物理 DELETE】，不是逻辑删除，而且必须如此。</b>
     * {@code sys_role_menu} 表<b>没有 {@code deleted} 列</b>
     * （{@code docs/sql/schema.sql}）。给它加上 {@code @TableLogic}
     * 会直接 {@code Unknown column 'deleted'}；而如果加的是列而不是注解，
     * 「取消勾选再重新勾上」会撞 {@code uk_role_menu} 唯一键 ——
     * 那条被逻辑删掉的行还在索引里。
     * <b>这张表的设计就是「关联不存在 = 行不存在」，不要改。</b>
     *
     * <p>它同时也被「删除角色」用到（级联清掉该角色的授权行）：
     * 角色被逻辑删除之后，这些行 join 不到 {@code sys_role}，本来就已经
     * 不产生任何权限了；留着的唯一后果是数据库里越积越多的垃圾行，
     * 以及「这个 menu_id 到底是谁授的」这种问题永远查不清。所以顺手删掉。
     *
     * @return 实际删除的行数。★ 可能是 0（这个角色本来就没勾过菜单），不是错误
     */
    @Delete("""
            DELETE FROM sys_role_menu
            WHERE role_id = #{roleId}
            """)
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 查几个角色合起来能拿到的权限点集合 —— 给 S13 的【自锁防护】用。
     *
     * <p><b>★ 它解决的问题：</b>管理员要改自己所属角色的菜单时，得先算清楚
     * 「改完之后我自己还剩哪些权限」。只看这一个角色是不够的 ——
     * 一个人可以同时是 ADMIN 和一个自定义角色，把自定义角色上的
     * {@code system:role:list} 去掉并不会让他失去它（ADMIN 还有）。
     * 那种情况下拦截就是误伤。
     * 所以要把「我<b>别的</b>角色能给的权限」和「目标角色的新权限」并起来看，
     * 见 {@code AdminRoleServiceImpl#guardSelfLock}。
     *
     * <p><b>★ 调用方必须保证 {@code roleIds} 非空</b>（空集合 → {@code IN ()}
     * 语法错误）。这不是敷衍：调用点天然有「没有别的角色」这个分支，
     * 在那里直接返回空集合比在 SQL 里兜底更清楚。
     *
     * <p><b>★★ 注意 {@code &lt;&gt;} 这种写法。</b>这个注解里包了
     * {@code <script>}，MyBatis 会把它当 <b>XML</b> 解析 ——
     * 而 XML 里 {@code <} 和 {@code &} 是非法的裸字符。
     * 直接写 {@code m.perms <> ''} 会报「元素内容必须由格式正确的字符数据组成」，
     * 而且报错指向的是整个注解，看不出是哪一行。
     * 转义成 {@code &lt;&gt;}（或者改用 {@code !=}）都可以。
     * ★ 没有 {@code <script>} 的普通 {@code @Select} 不会有这个问题 ——
     * 这是「什么时候要转义」的分界线。
     */
    @Select("""
            <script>
            SELECT DISTINCT m.perms
            FROM sys_role_menu rm
                     JOIN sys_menu m ON m.id = rm.menu_id
            WHERE rm.role_id IN
            <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
                #{roleId}
            </foreach>
              AND m.deleted = 0
              AND m.status = 1
              AND m.perms IS NOT NULL
              AND m.perms &lt;&gt; ''
            </script>
            """)
    List<String> selectPermsByRoleIds(@Param("roleIds") Collection<Long> roleIds);
}
