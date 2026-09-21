package com.novel.modules.system.vo;

import com.novel.modules.system.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 管理端的用户行 —— 用户列表页的每一行。
 *
 * <p><b>★★ 和 {@link UserVO} 是两个东西，不要合并：</b>
 * <pre>
 *   UserVO      「我」          一个用户看自己，带 permissions（前端拿它渲染菜单）
 *   AdminUserVO 「别人」        管理员看一列用户，带 roles（不含 permissions）
 * </pre>
 * 差别的根源是<b>每次请求的规模</b>：UserVO 一次只给一个人，查一遍权限点无所谓；
 * AdminUserVO 一页 10~100 个人，每人查一次权限点就是 N+1，
 * 而且管理端列表<b>根本不需要</b>展示权限点（要看得去角色页看）。
 *
 * <p><b>★★ 这个类里绝对不能出现 {@code password}。</b>
 * {@code SysUser} 实体上没有 {@code @JsonIgnore}，一旦有人图省事
 * {@code return user}，BCrypt 哈希就跟着返回了 —— 而它是<b>可以被离线暴力破解</b>的：
 * 拿到哈希就能在自己机器上不限次数地试密码，不受登录接口的任何限制。
 * 管理端列表一次返回上百条哈希，是最严重的一种泄露。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "管理端用户信息（不含密码）")
public class AdminUserVO {

    @Schema(description = "用户 ID", example = "2")
    private Long id;

    @Schema(description = "登录用户名", example = "author01")
    private String username;

    @Schema(description = "昵称", example = "测试作者")
    private String nickname;

    @Schema(description = "头像 URL")
    private String avatar;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    /**
     * 状态：1 正常，0 禁用（封禁）。
     *
     * <p>前端据此决定那一行显示「封禁」还是「解封」按钮 ——
     * 和作品状态一样，<b>给的按钮必须是点了能成的那个</b>。
     */
    @Schema(description = "状态：1 正常，0 已封禁", example = "1")
    private Integer status;

    /**
     * 该用户的角色码，如 {@code ["AUTHOR"]}。
     *
     * <p><b>★ 这是列表里唯一需要额外查询的字段</b>，而且必须是<b>批量</b>查的：
     * 先在 Service 里把本页所有 userId 收集起来一次查完，
     * 再逐条组装。逐条查就是经典的 N+1 —— 一页 20 条变成 21 次查询，
     * 而它「结果完全正确」，功能测试里永远发现不了。
     */
    @Schema(description = "角色码列表", example = "[\"AUTHOR\"]")
    private List<String> roles = Collections.emptyList();

    /**
     * 角色 id 列表 —— <b>给「编辑角色」弹窗做回显用</b>。
     *
     * <p><b>★ 为什么有了 {@code roles}（码）还要给 id：</b>
     * 弹窗里是一组 {@code el-checkbox}，它们的 value 是角色 <b>id</b>。
     * 只给码的话，前端每次打开弹窗都得拿码去反查 id ——
     * 而反查要依赖「角色列表已经加载好了」，是个隐藏的时序依赖。
     * 直接把 id 给出去，弹窗打开就能用。
     */
    @Schema(description = "角色 id 列表（编辑角色时回显）", example = "[2]")
    private List<Long> roleIds = Collections.emptyList();

    @Schema(description = "注册时间", example = "2026-09-01 10:00:00")
    private LocalDateTime createTime;

    /**
     * 从实体构造。
     *
     * <p>★ 刻意<b>不</b>提供「传一个 SysUser 就完事」的单参重载：
     * 角色必须由调用方补齐，留个只填一半的重载迟早会有人用错，
     * 然后得到一列角色为空的用户（界面上看起来像「这些人都没有角色」）。
     */
    public static AdminUserVO of(SysUser user, List<String> roles, List<Long> roleIds) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        vo.setRoles(roles == null ? Collections.emptyList() : roles);
        vo.setRoleIds(roleIds == null ? Collections.emptyList() : roleIds);
        return vo;
    }
}
