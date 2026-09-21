package com.novel.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.modules.system.entity.SysMenu;
import com.novel.modules.system.mapper.SysMenuMapper;
import com.novel.modules.system.service.AdminMenuService;
import com.novel.modules.system.vo.MenuVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单树的组装。
 *
 * <p><b>★★ 一次查询 + 内存组树，不做递归 SQL、也不按层逐级查。</b>
 * 菜单是几十行以内的字典数据（种子里 15 行、3 层），
 * 一次 {@code selectList} 就是 1 次网络往返；按层查是 N 次，
 * 每多一次往返就多一个「查第二层时第一层已经被改了」的不一致窗口，
 * 而拼出一棵自相矛盾的树（某个节点的父节点还没被查到）比多花几毫秒糟糕得多。
 *
 * <p><b>★ 为什么不手写 @Select 把 {@code ORDER BY sort, id} 写进 SQL：</b>
 * 手写就得自己写 {@code deleted = 0}（{@code @TableLogic} 对 {@code @Select}
 * 不生效）。漏写的后果是「已删除的菜单仍然出现在权限树里、仍然可以被勾选授权」——
 * 权限只会多不会少，看起来一切正常。用 {@code LambdaQueryWrapper}
 * 就没这个风险，见 {@code SysMenuMapper} 的类注释。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMenuServiceImpl implements AdminMenuService {

    private final SysMenuMapper menuMapper;

    @Override
    public List<MenuVO> tree() {
        /*
         * ★ 排序必须【两个字段】：先 sort、再 id。
         *
         * sort 上没有唯一约束（schema.sql 里只有一个普通索引 idx_parent_sort），
         * 管理员完全可以不改菜单的 sort，但种子数据里同级菜单的 sort
         * 本来就是 1、2、3 —— 只要有两条 sort 相同，MySQL 对「排序值相同的行」
         * 返回顺序就【不保证稳定】，表现为「刷新两次，树里两项顺序换了」。
         * 加 id 兜底之后排序就是确定的（id 是主键，全局唯一）。
         * 这和 CategoryServiceImpl#listByStatus 里那段注释是同一条理由。
         */
        List<SysMenu> menus = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));

        /*
         * ★ 用 LinkedHashMap 保持查询顺序 —— 同一层的兄弟节点就会按
         *   「sort, id」的次序出现在 children 里。用 HashMap 的话
         *   遍历顺序不确定，一棵树的顺序会在两次请求之间变。
         */
        Map<Long, MenuVO> voById = new LinkedHashMap<>();
        for (SysMenu menu : menus) {
            voById.put(menu.getId(), MenuVO.of(menu));
        }

        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO vo : voById.values()) {
            Long parentId = vo.getParentId();

            if (parentId == null) {
                // 顶级菜单。★ 「顶级」是 parentId 为 null，不是 0 —— 见 SysMenu 的注释
                roots.add(vo);
                continue;
            }

            MenuVO parent = voById.get(parentId);
            /*
             * ★ 自己当自己的父节点 —— 数据坏了，而且比孤儿更危险：
             *   下面那句 setChildren 会让 vo.children 里装着 vo 自己，
             *   Jackson 序列化时会【无限递归直到 StackOverflowError】，
             *   表现为这个接口 500。所以必须先拦下来。
             *
             * ★ 更长的环（A 的父是 B、B 的父是 A）不用单独处理：
             *   那两个节点都不会进 roots，也就都不会被序列化 ——
             *   结果是它们在这个接口里【看不见】，而不是崩掉。
             *   这是「坏数据」能造成的最好结果，而菜单在 P5 里是只读的
             *   种子数据（seed.sql），本来就构造不出环。
             */
            if (parent == vo) {
                log.warn("菜单的父节点是它自己，已挂到根节点 | menuId={} name={}", vo.getId(), vo.getName());
                roots.add(vo);
                continue;
            }
            if (parent == null) {
                /*
                 * ★★ 孤儿节点：parentId 指向一个【查不到的菜单】。
                 *
                 * 什么时候会发生：父菜单被逻辑删除（或停用后被前端隐藏），
                 * 子菜单还在。注意 @TableLogic 是自动加在查询上的，
                 * 所以「父菜单被删了」在 list() 里就等于「父菜单不存在」。
                 *
                 * ★ 为什么不直接 continue（丢掉这个节点）：
                 *   那会让这个菜单【静默消失】—— 它还在库里、还能被授权、
                 *   还在产生权限点，但任何界面都看不到它。
                 *   典型症状是「某个角色明明有 content:novel:list，
                 *   权限树里却找不到对应的勾」，而这条线索完全查不下去。
                 *
                 * ★ 为什么不抛异常：一份坏掉的种子数据不该让整个
                 *   「角色管理」页打不开 —— 管理员正是要进来修东西的。
                 *   挂到根节点上 + 一条 [WARN] 是这里能做到的最好的事：
                 *   问题看得见（树里多出来一项）、日志里有据可查、
                 *   而且不挡任何人的路。
                 */
                log.warn("菜单的父节点不存在，已挂到根节点 | menuId={} name={} parentId={}",
                        vo.getId(), vo.getName(), parentId);
                roots.add(vo);
                continue;
            }

            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(vo);
        }

        return roots;
    }
}
