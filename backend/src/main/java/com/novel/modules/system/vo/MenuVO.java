package com.novel.modules.system.vo;

import com.novel.modules.system.entity.SysMenu;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 菜单节点出参 —— <b>嵌套结构</b>，一次请求返回整棵树。
 *
 * <p><b>★ 为什么直接返回树，而不是「返回扁平列表让前端自己组」：</b>
 * 前端要的是 {@code el-tree} 的 {@code data}，那本来就是一个嵌套数组。
 * 返回扁平列表的话，每个调用方（角色赋权页、菜单管理页）都得再写一遍
 * 组树逻辑，而「父节点找不到怎么办」这种边界情况会各写各的。
 * 组树的规则只该有一份，放在后端。
 *
 * <p><b>★★ 叶子节点【不返回】{@code children}（而不是返回空数组）。</b>
 * 这是响应体的全局约定 {@code default-property-inclusion: non_null}
 * （{@code application.yml:35}）—— null 字段<b>直接从 JSON 里消失</b>。
 * 于是前端不能写 {@code node.children.length}，要写
 * {@code node.children?.length}；{@code el-tree} 本身对缺失的 children
 * 处理是对的（当成叶子），所以这里不用担心。
 * <p>反过来说：如果有人为了「让前端好写」把空数组塞进去，
 * 那每个叶子节点都会多一个 {@code "children": []}，
 * 而 Swagger 上看起来像是「所有节点都有子节点」。
 *
 * <p><b>★ 为什么不复用 {@code SysMenu} 实体直接返回：</b>
 * 实体上有 {@code deleted} 字段（虽然 {@code @TableField(select = false)}
 * 让 MP 不查它，但只要有人改用 {@code @Select} 手写，它就会冒出来），
 * 还有填充注解这类「只对写有意义」的东西。出参用独立的 VO 是这个项目
 * 一直的做法（{@code AdminUserVO} / {@code CommentVO} / {@code RoleVO}），
 * 目的是让「表结构」和「接口契约」能各自演化。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "菜单树节点")
public class MenuVO {

    @Schema(description = "菜单 ID", example = "2")
    private Long id;

    @Schema(description = "父菜单 ID，顶级为 null", example = "1")
    private Long parentId;

    @Schema(description = "菜单名", example = "作品管理")
    private String name;

    @Schema(description = "类型：1 目录 2 菜单 3 按钮。★ 目录行没有权限点", example = "2")
    private Integer type;

    /**
     * 前端路由。★ 目录行是 `/content` 这种分组标识，不是真实页面。
     *
     * <p>★★ <b>它必须和 {@code admin-web/src/router/index.js} 逐字一致</b>
     * （S14 对齐过一次：原来 12 个叶子里 11 个写的是 {@code /novel/list} 这种，
     * 而真实路由是 {@code /novel}）。理由见 {@code SysMenu#path}。
     */
    @Schema(description = "前端路由。★ 目录行是 `/content` 这种分组标识，不是真实页面",
            example = "/novel")
    private String path;

    @Schema(description = "权限标识。★ 目录行为 null，前端不要把它显示成空白，要显示成「—」",
            example = "content:novel:list")
    private String perms;

    @Schema(description = "图标名", example = "Reading")
    private String icon;

    @Schema(description = "排序值", example = "1")
    private Integer sort;

    @Schema(description = "是否显示：1 显示 0 隐藏", example = "1")
    private Integer visible;

    @Schema(description = "状态：1 启用 0 停用", example = "1")
    private Integer status;

    @Schema(description = "子节点。★ 叶子节点该字段【不存在】（响应体是 non_null 的）")
    private List<MenuVO> children;

    public static MenuVO of(SysMenu menu) {
        MenuVO vo = new MenuVO();
        vo.setId(menu.getId());
        vo.setParentId(menu.getParentId());
        vo.setName(menu.getName());
        vo.setType(menu.getType());
        vo.setPath(menu.getPath());
        vo.setPerms(menu.getPerms());
        vo.setIcon(menu.getIcon());
        vo.setSort(menu.getSort());
        vo.setVisible(menu.getVisible());
        vo.setStatus(menu.getStatus());
        return vo;
    }
}
