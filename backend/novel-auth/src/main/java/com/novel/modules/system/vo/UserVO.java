package com.novel.modules.system.vo;

import com.novel.common.constant.CommonConstant;
import com.novel.common.security.LoginUser;
import com.novel.modules.system.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 用户信息出参 —— 给前端看的「我」，也是会被存进 localStorage 的那份数据。
 *
 * <p><b>★★ 这个类里绝对不能出现 {@code password}。</b>
 * {@code SysUser} 实体上并没有 {@code @JsonIgnore}，一旦哪天有人图省事
 * 直接 {@code return user}，明文……不，密文就跟着返回了。
 * BCrypt 哈希虽然解不回明文，但它是<b>可以被离线暴力破解</b>的：
 * 拿到哈希就能在自己机器上不限次数地试密码，不受登录接口的任何限制。
 * 所以「转 VO」这一步不是形式主义，是必须走的一道关。
 *
 * <p><b>本类的字段是按「前端需要什么」倒推出来的</b>，不是把实体的字段抄一遍。
 * 前端的 {@code stores/user.js} 读的是 {@code nickname} / {@code role} /
 * {@code permissions} 这三个，所以它们必须有。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "用户信息（不含密码）")
public class UserVO {

    @Schema(description = "用户 ID", example = "1")
    private Long id;

    @Schema(description = "登录用户名", example = "admin")
    private String username;

    @Schema(description = "昵称", example = "系统管理员")
    private String nickname;

    /**
     * 头像 URL，形如 {@code /uploads/avatar/2026/09/21/ab12.jpg}；没传过是 {@code null}。
     *
     * <p><b>★★ 前端必须写 {@code userInfo?.avatar || 默认头像}，不能写
     * {@code avatar === null} 来判断。</b>这份数据会被整个存进
     * {@code localStorage[novel_user]}（见 {@code stores/user.js}），
     * 而那份 JSON 是<b>上一次登录时</b>写下的 —— P6 之后新增的这个 key
     * 在老用户手里<b>压根不存在</b>，取出来是 {@code undefined} 而不是 {@code null}，
     * 写 {@code === null} 的分支永远不成立，页面上就是一个不出图的空 {@code <img>}。
     * （同 {@code CommentVO#nickname} 那条「写 {@code ?? '已注销'}，不要写 {@code === null}」。）
     */
    @Schema(description = "头像 URL，未设置为 null", example = "/uploads/avatar/2026/09/21/ab12.jpg")
    private String avatar;

    /**
     * 用户的全部角色码，如 {@code ["ADMIN"]}、{@code ["AUTHOR","READER"]}。
     *
     * <p><b>★ 这个才是唯一的事实来源</b>（数据库里就是多对多）。
     * 后面那个 {@link #role} 只是为了迁就前端已有代码而额外给的便捷字段。
     */
    @Schema(description = "全部角色码", example = "[\"ADMIN\"]")
    private List<String> roles = Collections.emptyList();

    /**
     * 主角色 —— 由 {@link #roles} 派生出来的<b>单个</b>角色码。
     *
     * <p><b>为什么有了 {@code roles} 还要给这个：</b>
     * 前端的 {@code stores/user.js} 里写的是
     * {@code userInfo.role === 'ADMIN'}，两个路由守卫也是这么判的
     * （P0 阶段已经验收通过）。后端直接给一个「最高角色」，
     * <b>这些已经跑通的代码一行都不用改</b>；同时 {@code roles} 也返回，
     * 等 P2 做多角色界面时直接用真的，不用再改接口。
     *
     * <p>取值规则见 {@link #primaryOf(List)}：{@code ADMIN > AUTHOR > READER}。
     * 取最高而不是取第一个 —— 因为 {@code sys_user_role} 的查询顺序
     * 由数据库决定，不保证稳定，取第一个会导致同一个人刷新两次
     * 看到的角色不一样。
     */
    @Schema(description = "主角色（由 roles 派生的最高角色）", example = "ADMIN")
    private String role;

    @Schema(description = "权限码列表，三段式 域:资源:动作", example = "[\"content:novel:list\"]")
    private List<String> permissions = Collections.emptyList();

    /* ==================== 构造 ==================== */

    /**
     * 从 Redis 里的登录态构造 —— 登录成功、以及 {@code /api/auth/me} 用这个。
     *
     * <p>注意它<b>不查数据库</b>：{@link LoginUser} 是拦截器从 Redis 取的，
     * 里面已经有角色和权限了，再查一遍纯属浪费一次 IO。
     */
    public static UserVO from(LoginUser loginUser) {
        UserVO vo = new UserVO();
        vo.setId(loginUser.getUserId());
        vo.setUsername(loginUser.getUsername());
        vo.setNickname(loginUser.getNickname());
        vo.setAvatar(loginUser.getAvatar());
        vo.setRoles(loginUser.getRoles() == null ? Collections.emptyList() : loginUser.getRoles());
        vo.setPermissions(loginUser.getPermissions() == null
                ? Collections.emptyList() : loginUser.getPermissions());
        vo.setRole(primaryOf(vo.getRoles()));
        return vo;
    }

    /**
     * 从实体构造 —— 注册接口用这个（那时还没有登录态）。
     *
     * @param roles       该用户刚被授予的角色码
     * @param permissions 该用户的权限码（新注册的 READER 是空的）
     */
    public static UserVO of(SysUser user, List<String> roles, List<String> permissions) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRoles(roles == null ? Collections.emptyList() : roles);
        vo.setPermissions(permissions == null ? Collections.emptyList() : permissions);
        vo.setRole(primaryOf(vo.getRoles()));
        return vo;
    }

    /**
     * 按「权限从大到小」取最高的那个角色。
     *
     * <p>顺序不能随便定：ADMIN 必须排在 AUTHOR 前面，
     * 否则一个既是管理员又是作者的人会被判定成作者，
     * 进不了管理端 —— 而且这种 bug 只在多角色账号上出现，很难发现。
     *
     * <p>一个角色都没有时返回 {@code null}（前端 {@code role} 会是空字符串）。
     * 正常注册流程不会出现这种情况（至少有个 READER），
     * 但如果有人手工删了 {@code sys_user_role} 的记录就会。
     */
    private static String primaryOf(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        if (roles.contains(CommonConstant.ROLE_ADMIN)) {
            return CommonConstant.ROLE_ADMIN;
        }
        if (roles.contains(CommonConstant.ROLE_AUTHOR)) {
            return CommonConstant.ROLE_AUTHOR;
        }
        if (roles.contains(CommonConstant.ROLE_READER)) {
            return CommonConstant.ROLE_READER;
        }
        // 都不认识（数据库里加了新角色但这里没跟着改）时，取第一个，别返回 null
        return roles.get(0);
    }
}
