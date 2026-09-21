package com.novel.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色-菜单关联，对应 {@code sys_role_menu}。
 *
 * <p><b>★★ 这个实体有两个「缺字段」，都是故意的，加回去就会炸。</b>
 * 它和 {@code SysUserRole} 是同一类东西（纯关联表），规则完全一样，
 * 两个类可以对着看：
 * <ol>
 *   <li><b>没有 {@code @TableLogic} / {@code deleted}。</b>
 *       {@code sys_role_menu} 表<b>没有 {@code deleted} 列</b>
 *       （见 {@code docs/sql/schema.sql}）。
 *       给它挂上 {@code @TableLogic}，所有查询都会变成
 *       {@code Unknown column 'deleted' in 'where clause'}。
 *       <br>「给角色重新勾选菜单」只能<b>物理 DELETE + INSERT</b>，
 *       见 {@code SysRoleMenuMapper#deleteByRoleId}。
 *       <b>★ 紧挨着它的 {@code sys_menu} 和 {@code sys_role} 都有这一列 ——
 *       三张表长得几乎一样、规则却是反的，这是本模块最容易搞混的一处。</b></li>
 *   <li><b>没有 {@code updateTime}。</b>表里只有 {@code create_time}。
 *       {@code MybatisMetaObjectHandler} 是按字段名无差别填充的，
 *       多一个字段就会多一列 {@code Unknown column}。</li>
 * </ol>
 *
 * <p><b>★ {@code createTime} 上没有 {@code @TableField(fill = INSERT)}：</b>
 * 理由和 {@code SysUserRole} 完全相同 —— 本表唯一的写路径是
 * {@code SysRoleMenuMapper#insertBatch}（手写 SQL），
 * 时间走数据库的 {@code DEFAULT CURRENT_TIMESTAMP}。
 * 挂上填充注解会让人以为 {@code baseMapper.insert()} 也走同一条路，
 * 于是「时间从哪来」就有了两个答案。
 *
 * @author MickeyChen
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenu implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色 {@code sys_role.id} */
    private Long roleId;

    /** 菜单 {@code sys_menu.id} */
    private Long menuId;

    /** 授权时间。★ 由数据库默认值填充，见类注释最后一段 */
    private LocalDateTime createTime;
}
