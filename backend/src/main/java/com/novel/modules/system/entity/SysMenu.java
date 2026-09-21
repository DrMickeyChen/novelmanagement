package com.novel.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 菜单 / 权限点，对应 {@code sys_menu}。
 *
 * <p><b>★ 这一张表有 {@code deleted} 列，手写 SQL 时【必须自己写
 * {@code deleted = 0}】</b>（{@code @TableLogic} 只对 MP 自动生成的方法生效）。
 * 紧挨着它的 {@code sys_role_menu} <b>没有</b>这一列 ——
 * 两张表长得几乎一样、规则却是反的，写 join 之前先看清是哪个表。
 *
 * <p><b>★★ 本类在 P5 里是【只读】的。</b>
 * 菜单树是种子数据（{@code seed.sql} 里那 15 行），P5 只做
 * 「按角色勾选菜单」，<b>不做菜单本身的增删改</b>（那是「按需」项）。
 *
 * <p>因此这个类<b>刻意没有挂 {@code @TableField(fill = ...)} 填充注解</b>：
 * 挂了之后会让人以为已经有写路径了（{@code baseMapper.insert()} 也能写对时间），
 * 而实际上 P5 一行都不会往 {@code sys_menu} 里写。
 * 将来真要补菜单 CRUD 时，照着 {@code SysRole} 把
 * {@code @TableField(fill = INSERT)} / {@code INSERT_UPDATE} 补上即可 ——
 * 那时填充生效，时间的来源也仍然只有 {@code MybatisMetaObjectHandler} 一处。
 *
 * <p><b>★ 为什么 {@code type} / {@code visible} 这两个字段界面上一时用不到也要留着：</b>
 * 它们决定「这一行在树里怎么显示」——{@code type=1} 是目录（没有权限点），
 * {@code type=2} 才是可勾选的菜单。前端要按它画不同的图标、
 * 并且<b>不给目录行显示 perms</b>（目录行的 perms 本来就是空的）。
 * 少返回一个字段，前端就只能自己猜。
 *
 * @author MickeyChen
 */
@Data
@TableName("sys_menu")
public class SysMenu implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 父菜单 id，<b>顶级菜单是 {@code null}（不是 0）</b>。
     *
     * <p>★ 建树时必须区分「是顶级」和「父节点查不到」这两种情况 ——
     * 前者正常，后者是数据坏了（见 {@code AdminMenuServiceImpl}）。
     * 用 0 当哨兵值就区分不出来了。
     */
    private Long parentId;

    /** 菜单名，如「作品管理」 */
    private String name;

    /** 类型：1 目录 2 菜单 3 按钮。★ 目录行没有权限点（perms 为空） */
    private Integer type;

    /**
     * 前端路由，如 {@code /novel}。★ 目录行是 {@code /content} 这种前缀，不是真实页面。
     *
     * <p><b>★★ 必须和 {@code admin-web/src/router/index.js} 里的真实路由逐字一致。</b>
     * 它今天还没有消费者（左侧菜单走的是 {@code AdminLayout} 里那份写死的表，
     * 按叶子的 {@code perms} 过滤），所以写错了也不会有人发现 ——
     * 事实上原来 12 个叶子里有 11 个写的是 {@code /x/list}（照着同行的 perms
     * 抄形状抄出来的），S14 才对齐。现在菜单管理页会把这一列显示出来，
     * 二期做动态菜单时它就是路由键。
     */
    private String path;

    /**
     * 权限标识，如 {@code content:novel:list}。
     *
     * <p>★ 它是「这个菜单要什么权限才能看见」的唯一依据 ——
     * 前端 {@code AdminLayout} 按它过滤左侧菜单，
     * 后端 {@code SysUserRoleMapper#selectPermsByUserId} 把一组角色能拿到的
     * perms 并起来交给登录接口。**目录行为 null**。
     */
    private String perms;

    /** 图标名。★ admin-web 的侧栏没有用它（用的是自己那份写死的菜单表），留着给将来的动态菜单 */
    private String icon;

    /** 排序值。★ 和 {@code novel_category.sort} 一样没有唯一约束，所以排序时一定要再按 id 兜底 */
    private Integer sort;

    /**
     * 是否显示：1 显示 0 隐藏。
     *
     * <p>★ <b>当前没有任何代码读它</b>（种子里也没写，全是默认值 1），
     * 是给二期的动态菜单预留的 —— 想下掉一个入口又不想删掉这行数据时用。
     * 所以菜单管理页**刻意没有把它显示成一列**：一列「永远是 1、谁都不读」
     * 的东西摆在界面上，看起来像有含义，实际没有。
     */
    private Integer visible;

    /** 状态：1 启用 0 停用。★ 停用后 {@code selectPermsByUserId} 会跳过它，等于收回这个权限 */
    private Integer status;

    /** ★ 由数据库默认值 {@code CURRENT_TIMESTAMP} 填充，见类注释第二段 */
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
