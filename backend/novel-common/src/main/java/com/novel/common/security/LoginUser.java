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
     * 头像 URL，展示用（P6 加）。
     *
     * <p><b>★ 这个值只在【登录那一刻】写入，所以它是会过期的：</b>用户在个人中心
     * 换完头像后，库里和前端 {@code localStorage} 都更新了，但这份 Redis 里的
     * 登录态还是旧值 —— 谁读它谁拿到旧头像（{@code /api/auth/me} 就是）。
     * 想修的话要给 {@code TokenStore} 加一个「只刷昵称/头像、<b>不动 TTL</b>」的方法
     * （直接 {@code save} 会把会话寿命重新拉满 7 天，等于把「改昵称」变成延长登录的手段）。
     * ★ P6 没做，因为 {@code api/auth.js} 里的 {@code getMe()} 三端都零调用点，
     * 这个陈旧值浮不到界面上；界面上的头像走的是下面这条不会过期的路。
     *
     * <p>★ 界面上要立刻生效，靠的是接口返回的新 UserVO（{@code modules/system/vo/UserVO}）：
     * 前端拿它调 {@code stores/user.js} 已有的 {@code setLoginInfo} 覆盖 localStorage。
     *
     * <p><b>★★ M1：上面那个 {@code @link} 被【刻意降级成了纯文本】。</b>
     * 它原来是 {@code {@link com.novel.modules.system.vo.UserVO}} —— 那是
     * {@code novel-common} 对整个项目里【唯一一处】指向业务模块的引用。
     * M1 把 {@code modules/system} 搬进 novel-auth 之后，novel-common 的
     * classpath 上再也没有那个类了（common 是个库，它依赖不了任何服务）。
     * <p>Javadoc 的 {@code @link} 指不到类<b>不会让编译失败</b>，只会在生成文档时
     * 报一句警告 —— 也就是说，如果留着它，这件事会以「一条没人看的警告」的形式
     * 潜伏下去。而改掉之后，<b>novel-common 对业务模块的引用数就是零</b>，
     * 「common 里不会混进业务类」从此是一条可以被 grep 验证的硬事实：
     * <pre>grep -rn "com\.novel\.modules" backend/novel-common/   # 期望：零命中</pre>
     *
     * <p>★ 加字段对 Redis 里的<b>老缓存是安全的</b>（Jackson 读到缺字段的 JSON 得到
     * {@code null}）；但<b>不要删字段或改名字</b> —— 老缓存的 JSON 里有它、新类里没有，
     * 反序列化会抛 {@code UnrecognizedPropertyException}，症状是「所有人突然被登出」。
     */
    private String avatar;

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
     * {@code HeaderAuthenticationFilter}（M1 前叫 {@code JwtAuthenticationFilter}）。
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
