package com.novel.modules.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 角色，对应 {@code sys_role}。
 *
 * <p><b>★ 这一张表有 {@code deleted} 列，手写 SQL 时【必须自己写
 * {@code deleted = 0}】</b>（{@code @TableLogic} 只对 MP 自动生成的方法生效）。
 * 紧挨着它的 {@code sys_user_role} / {@code sys_role_menu} <b>都没有</b>这一列 ——
 * 三张表长得几乎一样、规则却是反的，这是本模块最容易搞混的一处，
 * 写 join 之前先看清是哪个表。
 *
 * <p><b>★ {@code roleCode} 上有唯一索引 {@code uk_role_code}，且它不含 {@code deleted}。</b>
 * 和 {@code novel_category.uk_name} 是同一个坑：逻辑删除只是把 {@code deleted} 置 1，
 * 那一行还在索引里 —— 删掉 {@code ADMIN} 之后再也建不出 {@code ADMIN}。
 * 所以内置角色（ADMIN / AUTHOR / READER）<b>一律禁止删除</b>，
 * 见 {@code AdminRoleService}。
 *
 * @author MickeyChen
 */
@Data
@TableName("sys_role")
public class SysRole implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 角色标识，如 {@code ADMIN} / {@code AUTHOR} / {@code READER}。
     *
     * <p><b>★ 代码里永远按这个字段查角色，不要写死 {@code role_id = 1}。</b>
     * 自增 id 是数据库的实现细节，换一套环境、重新灌一次种子数据就可能变；
     * 而 {@code role_code} 上有唯一索引，是业务上的稳定标识。
     * 登录时 {@code SysRoleMapper#selectRoleIdByCode} 就是这么做的。
     */
    private String roleCode;

    /** 角色名（给人看的），如「系统管理员」 */
    private String roleName;

    private String remark;

    /**
     * 状态：1 启用，0 停用。
     *
     * <p><b>★ 停用会立刻改变鉴权结果。</b>{@code SysUserMapper#selectRoleCodesByUserId}
     * 里带了 {@code r.status = 1} —— 停用某个角色的那一刻，所有凭它登录的人
     * 在<b>下一次请求</b>就会失去该角色（身份每次请求都从 Redis 读，
     * 但 Redis 里的角色是登录时写进去的）。
     * 见 {@code AdminRoleService} 里的「改角色要踢下线」那一条。
     */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
