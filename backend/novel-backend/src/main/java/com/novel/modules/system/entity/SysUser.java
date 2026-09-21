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
 * 用户表实体，对应 {@code sys_user}。
 *
 * @author MickeyChen
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名，全局唯一 */
    private String username;

    /**
     * BCrypt 加密后的密码，形如 {@code $2a$10$Jwayv0o7...}。
     *
     * <p><b>安全约定（务必遵守）：</b>
     * <ul>
     *   <li>数据库里存的永远是密文，任何时候都不要存明文</li>
     *   <li>这个对象<b>绝对不能直接返回给前端</b> —— 所以要转成 VO，
     *       或者加 {@code @JsonIgnore}；直接 return 实体是泄露密码最常见的方式</li>
     *   <li>即使前端传了 password 字段也只是用来和数据库里的密文比对，
     *       不会被「解密」—— BCrypt 是单向哈希，本来就解不回来</li>
     * </ul>
     */
    private String password;

    /** 昵称（展示用） */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 状态：1 正常，0 禁用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记：0 未删除，1 已删除。
     *
     * <p>加了 {@code @TableLogic} 之后，所有查询会自动带上 {@code WHERE deleted = 0}，
     * 删除操作也会自动变成 {@code UPDATE ... SET deleted = 1} —— 你不用手写。
     */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
