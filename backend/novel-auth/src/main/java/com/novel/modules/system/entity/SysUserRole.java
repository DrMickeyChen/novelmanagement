package com.novel.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户-角色关联，对应 {@code sys_user_role}。
 *
 * <p><b>★★ 这个实体有两个「缺字段」，都是故意的，加回去就会炸：</b>
 * <ol>
 *   <li><b>没有 {@code @TableLogic} / {@code deleted}。</b>
 *       {@code sys_user_role} 表<b>没有 {@code deleted} 列</b>
 *       （见 {@code docs/sql/schema.sql}）。
 *       给它挂上 {@code @TableLogic}，所有查询都会变成
 *       {@code Unknown column 'deleted' in 'where clause'}。
 *       <br>想「删掉某人的角色」只能<b>物理 DELETE</b>，
 *       见 {@code SysUserRoleMapper#deleteUserRoles}。</li>
 *   <li><b>没有 {@code updateTime}。</b>表里只有 {@code create_time}。
 *       {@code MybatisMetaObjectHandler} 是无差别填充的（按字段名找），
 *       多一个字段就会多一列 {@code Unknown column}。
 *       <br>{@code ReadHistory} 和 {@code AuditLog} 是同一类坑的另两个方向，
 *       三个实体可以对着看：<b>实体字段必须和表列一一对应，不多不少。</b></li>
 * </ol>
 *
 * <p><b>★ 为什么 {@code createTime} 上【没有】{@code @TableField(fill = INSERT)}：</b>
 * 本表唯一的写路径是 {@code SysUserRoleMapper#insertUserRole}（手写 SQL），
 * 时间走数据库的 {@code DEFAULT CURRENT_TIMESTAMP}。
 * 挂上填充注解会让人以为 {@code baseMapper.insert()} 也能写对时间 ——
 * 能写，但走的是应用时间，和手写 SQL 那条路的数据库时间混在一起，
 * 时间的来源就有两个了。这和 {@code ReadHistory.updateTime} 是同一条理由。
 *
 * @author MickeyChen
 */
@Data
@TableName("sys_user_role")
public class SysUserRole implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 {@code sys_user.id} */
    private Long userId;

    /** 角色 {@code sys_role.id} */
    private Long roleId;

    /** 授权时间。★ 由数据库默认值填充，见类注释最后一段 */
    private LocalDateTime createTime;
}
