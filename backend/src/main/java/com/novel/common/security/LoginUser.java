package com.novel.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 已登录用户的身份载体 —— 登录成功后存进 Redis，之后每个请求从 Redis 取出来。
 *
 * <p><b>它和 JWT 的分工（不要混）：</b>
 * <pre>
 *   JWT 里装：userId、jti、username   ← 只够「认出你是谁」，很短小
 *   本类装：  上面那些 + 角色 + 权限   ← 够「判断你能干什么」
 * </pre>
 * 为什么不把这些都塞进 JWT？因为 JWT 一旦签发就<b>改不了</b> —— 管理员把你的
 * 角色从 READER 改成 AUTHOR，你手里那个 token 里还是旧的，只能等你自然过期
 * 或者重新登录。放 Redis 里就能立刻改、立刻生效（改完删一次 key 就行）。
 *
 * <p><b>★★ 为什么这个类必须放在 {@code com.novel.} 包下：</b>
 * {@code RedisConfig} 里配的是 {@code BasicPolymorphicTypeValidator} <b>白名单</b>，
 * 只允许 {@code com.novel.} / {@code java.util.} / {@code java.time.} /
 * {@code java.lang.} 这四个前缀的类型被还原。放到别的包（比如 {@code com.example}）
 * 会在读 Redis 时抛 {@code InvalidTypeIdException} —— 而且报错信息
 * 完全指不到配置这里，很难查。
 *
 * <p>那个白名单<b>是故意的，不要去放宽它</b>：JSON 里带类名再按类名加载，
 * 是 Jackson 反序列化攻击的经典入口。
 *
 * <p>本类实现 {@code Serializable} 是为了万一哪天换回 JDK 序列化也能用，
 * 更重要的是给缓存对象一个明确的态度：<b>它是可序列化的值对象</b>。
 *
 * @author MickeyChen
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID，对应 {@code sys_user.id} */
    private Long userId;

    /** 登录名 */
    private String username;

    /** 昵称，展示用 */
    private String nickname;

    /**
     * JWT 的唯一编号（{@code jti} 声明）。
     *
     * <p><b>★ 多端并存靠的就是它。</b>
     * Redis key 是 {@code novel:auth:token:{userId}:{jti}}，
     * 同一个账号在手机和电脑上登录会拿到两个不同的 jti，
     * 于是 Redis 里是两把 key —— 登出手机只删得掉手机那把，
     * 电脑不受影响。如果 key 里没有 jti，一天只能有一个设备在线。
     */
    private String jti;

    /**
     * 角色码列表，如 {@code ["ADMIN"]}、{@code ["READER","AUTHOR"]}。
     *
     * <p>值来自 {@code sys_role.role_code}，是<b>不带 {@code ROLE_} 前缀</b>
     * 的裸码（与数据库的数据契约一致，不要改）。
     * 前缀在构建 Spring Security 的权限时现拼，见
     * {@code JwtAuthenticationFilter}。
     *
     * <p>一个用户可能有多个角色（{@code sys_user_role} 是多对多），
     * 所以这里是 List 而不是单个 String。
     */
    private List<String> roles = Collections.emptyList();

    /**
     * 权限码列表，如 {@code ["content:novel:list", "system:user:list"]}。
     *
     * <p>来自 {@code sys_menu.perms}，是<b>三段式</b> {@code 域:资源:动作}，
     * 通过 {@code sys_role_menu} 关联到角色。
     * 用于更细的控制，例：{@code @PreAuthorize("hasAuthority('system:user:list')")}。
     */
    private List<String> permissions = Collections.emptyList();
}
