package com.novel.modules.system.service;

import com.novel.modules.system.vo.MenuVO;

import java.util.List;

/**
 * 管理端 —— 菜单（读）。
 *
 * <p><b>★ P5 只做「读整棵树」，不做菜单的增删改</b>（那是计划里
 * 「按需」的一项）。所以这个接口只有一个方法，而且它是只读的。
 *
 * <p><b>★ 它服务的两个页面：</b>
 * <ol>
 *   <li>角色管理页的权限树 —— 用这棵树当 {@code el-tree} 的 data，
 *       再拿 {@code AdminRoleService#menuIdsOf} 的结果当默认勾选项；</li>
 *   <li>菜单管理页（S14）—— <b>只读展示</b>。它存在的意义是让人
 *       「看得见权限点是从哪来的」，而不是编辑它们。</li>
 * </ol>
 *
 * @author MickeyChen
 */
public interface AdminMenuService {

    /**
     * 整棵菜单树（嵌套返回，按 sort 升序、同序按 id 升序）。
     *
     * <p>★ 包含<b>已停用</b>的菜单（{@code status = 0}）：管理员的权限树里
     * 必须能看见它们，否则「某个角色为什么有这个权限」会变成一个
     * 在界面上无法回答的问题。停用的菜单在树里标灰。
     */
    List<MenuVO> tree();
}
