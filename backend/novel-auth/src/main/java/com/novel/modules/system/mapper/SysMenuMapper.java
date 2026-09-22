package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;

/**
 * 菜单 / 权限点 Mapper，对应 {@code sys_menu}。
 *
 * <p><b>★ 这个类里一行 SQL 都没有，这不是没写完。</b>
 * P5 对菜单只有一种读法：<b>全量查出来，在内存里组树</b>。
 * 那件事用 MP 自带的 {@code selectList(wrapper)} 就够了 ——
 * 它自动带 {@code deleted = 0}（{@code @TableLogic}），
 * 而排序用一个 {@code LambdaQueryWrapper} 表达得比 SQL 字符串清楚。
 *
 * <p><b>★★ 为什么不写「按 parent_id 递归查询」或「一层一层查」：</b>
 * 菜单是<b>个位数到几十行</b>的字典数据，一次全查出来是 1 次网络往返；
 * 而递归查询是 N 次（15 个菜单最多 3 层，也要 4 次），
 * 每多一次往返就多一次「查第二层的时候第一层已经被改了」的不一致窗口。
 * 组树是纯内存操作，几微秒。
 *
 * <p><b>★ 那为什么不写 {@code @Select} 手写这条查询：</b>
 * 手写就得自己写 {@code deleted = 0}（{@code @TableLogic} 对 {@code @Select}
 * 不生效），而这里一旦漏写，「已删除的菜单仍然可勾选、仍然授权」——
 * 症状是权限只多不少，看起来一切正常。用 MP 的方法就没这个风险。
 * <b>能不给手写 SQL 机会的地方，就不给。</b>
 *
 * <p>（对比 {@code SysRoleMenuMapper} —— 那个类必须手写，因为
 * {@code sys_role_menu} 没有 {@code deleted} 列，MP 的自动 SQL 反而会出错。
 * 两个类是「有 deleted 列」和「没有 deleted 列」的两种写法样板。）
 *
 * @author MickeyChen
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {
}
