package com.novel.common.security;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <b>网关 → 下游服务之间那份「身份」的编解码，全项目只此一份。</b>
 *
 * <p>M1 起认证搬到了网关：网关验签、查 Redis，然后把 {@link LoginUser} 拆成
 * 一组请求头写进转发的请求里；下游服务再把它拼回一个 {@code LoginUser}。
 * <b>写的一端和读的一端必须对同一份格式达成一致</b>，而它们分别在
 * 四个进程里（网关、novel-auth、novel-content、novel-interaction）。
 *
 * <p><b>★★ 为什么必须是「一个类」而不是「三处各写一遍」：</b>
 * 这里没有编译期约束 —— 网关把昵称写进 {@code X-Nickname}、下游从
 * {@code X-NickName} 读，两边都能编译、都能启动、都不报错。
 * 症状只有一个：那个字段永远是 {@code null}。而 {@code null} 在业务上
 * 几乎总是「合法的」值（没有昵称的用户、没有头像的用户都真实存在），
 * 所以没有任何断言会失败，只能靠人去发现。
 * <p>更糟的是 {@code X-Jti} 那一个（见 {@link #JTI}）——
 * 它丢了之后界面看起来完全正常，只有「退出登录」悄悄失效。
 *
 * <p><b>★ 三个使用点，全部指向本类：</b>
 * <pre>
 *   novel-gateway   JwtAuthGlobalFilter    先 strip() 再 write()     ← 写的唯一出处
 *   三个服务         HeaderAuthenticationFilter  read()              ← 读的唯一出处
 *   Feign 出站       各自的 RequestInterceptor    write()             ← 服务间调用也走同一套
 * </pre>
 *
 * <p><b>★ 为什么不放在 {@code com.novel.common.constant} 里：</b>
 * 它不只是一组字符串常量，还带着「怎么编码、怎么校验」的行为。
 * 拆成「常量在一处、编解码在另一处」正是上面那种漂移的温床。
 *
 * @author MickeyChen
 */
public final class IdentityHeaders {

    /*
     * ============================================================
     * 头名清单 —— 加一个头，必须同时想清楚「漏了会怎样」
     * ============================================================
     */

    /** 用户 ID（{@code sys_user.id}）。★ 漏了 → {@code UserContext.requireUserId()} 拿不到，所有写接口失败。 */
    public static final String USER_ID = "X-User-Id";

    /** 登录名。★ 漏了 → {@code /api/auth/me} 的 username 变 null。 */
    public static final String USERNAME = "X-Username";

    /**
     * 昵称（展示用）。
     *
     * <p>★ 它本来就是「登录那一刻的快照」，会过期 —— 改完昵称同一个会话里
     * 读到的还是旧值。<b>这不是 M1 引入的行为</b>：改造前 {@code LoginUser}
     * 也是从 Redis 里读的同一份快照。M1 只是换了它进进程的载体。
     */
    public static final String NICKNAME = "X-Nickname";

    /**
     * 头像 URL（展示用）。
     *
     * <p>★ <b>漏了会怎样容易被忽略：</b>它和 {@code NICKNAME} 一样是快照，
     * 所以「显示成 null」看起来像「这个用户没设过头像」。但改造前它是有值的
     * —— 漏传它是一个<b>可观察的行为变化</b>，只是没人会怀疑到这里。
     */
    public static final String AVATAR = "X-Avatar";

    /**
     * <b>★★★ JWT 的唯一编号 —— 这一条是 M1 里最容易静默出事的地方。</b>
     *
     * <p>登出（{@code AuthServiceImpl#logout}）<b>不解析 Authorization 头</b>，
     * 它读的是 {@code UserContext.get().getJti()}，然后用
     * {@code TokenStore.remove(userId, jti)} 删 Redis 里的那把 key。
     *
     * <p>改造前 {@code UserContext} 由 {@code JwtAuthenticationFilter} 从
     * JWT 的 claims 里填；M1 之后由网关的请求头填。<b>字段一个都不能少</b> ——
     * 少了这个头，{@code getJti()} 返回 {@code null}，删的就是
     * {@code novel:auth:token:{userId}:null} 这把根本不存在的 key。
     *
     * <p><b>症状是「点了退出，但还是登录着」</b>，而服务端日志那行
     * {@code 退出登录 | userId=3 jti=null 记录存在=false} <b>看着完全正常</b>
     * —— 它老老实实报告了「没删掉任何东西」，只是没人会去读这一行。
     * 更麻烦的是下一个请求仍然能过网关的 Redis 校验，所以「退不出去」
     * 这件事在前端看起来像「按钮没反应」。
     */
    public static final String JTI = "X-Jti";

    /**
     * 角色码，多个用逗号连接（{@code ADMIN,AUTHOR}）。
     *
     * <p>★ 漏了 → <b>所有 URL 闸失效</b>：{@code hasRole("ADMIN")} 全部不匹配，
     * 于是 ADMIN 访问 {@code /api/admin/**} 会拿到 403。症状是「管理员被自己的
     * 后台拦住」，看起来像权限数据坏了，其实是这个头没传。
     */
    public static final String ROLES = "X-Roles";

    /**
     * 权限码，多个用逗号连接。不带 {@code ROLE_} 前缀（理由见
     * {@code buildAuthorities} 的注释）。
     *
     * <p>★ 现状：全仓<b>零个</b>活跃的 {@code @PreAuthorize}，所以今天漏了它
     * 没有任何影响。留着是为了 M2/M3 拆出 content 之后，方法级鉴权
     * （{@code hasAuthority}）不用再回来补一个头 —— 那时候补，
     * 又是一次「改了写端忘了改读端」的机会。
     */
    public static final String PERMISSIONS = "X-Permissions";

    /**
     * <b>服务间共享密钥</b>，不是用户身份，而是「这个请求来自我们自己的服务」的凭证。
     *
     * <p>M1 的内部信任边界（两个用途）：
     * <ol>
     *   <li>网关转发时带上它 → 下游才肯相信同一请求里的 {@code X-User-*} 是真的；</li>
     *   <li>Feign 出站时带上它 → {@code /internal/**} 的内部接口才会应答。</li>
     * </ol>
     *
     * <p><b>★ 没有它，直连 {@code :8082} 发一个
     * {@code -H "X-User-Id: 1" -H "X-Roles: ADMIN"} 就是管理员了。</b>
     * 开发期 8081/8082/8083 是开着的（排错要直连），所以这个头是必需的；
     * 生产上还有网络隔离兜底（只有网关对外）。
     */
    public static final String INTERNAL_TOKEN = "X-Internal-Token";

    /**
     * 所有由「我们」管理的头的名字。
     *
     * <p><b>★ 网关转发前要把这一组【整个删掉】再写自己的</b>（见
     * {@link #strip}）—— 这就是「先剥后写」。少剥一个，任何人都能用
     * {@code curl -H "X-User-Id: 1"} 变成管理员。
     */
    public static final List<String> ALL = List.of(
            USER_ID, USERNAME, NICKNAME, AVATAR, JTI, ROLES, PERMISSIONS, INTERNAL_TOKEN);

    /**
     * 多值头里元素之间的分隔符。<b>必须是【字面】逗号，绝不能跟着值一起被编码。</b>
     *
     * <p>它存在的唯一理由是给 {@link #join} / {@link #split} 一个共同的名字 ——
     * 这一对方法必须对称，改了写端不改读端就是「元素个数错了」那种静默故障
     * （完整症状见 {@link #write} 里 {@code putList} 那一段）。
     *
     * <p>★ 逗号是合法的 HTTP 头字符，本来就被规范当作列表分隔符
     * （{@code Accept: text/html, application/json}），所以这里不编码它
     * 不是「偷懒」，而是顺着规范走。
     */
    private static final String LIST_SEPARATOR = ",";

    private IdentityHeaders() {
    }

    // ============================================================
    // 写：LoginUser → 请求头
    // ============================================================

    /**
     * 把登录态写进一组头里。{@code target} 由调用方给（网关给的是它要转发的
     * 那个请求的头，Feign 给的是出站请求的头）。
     *
     * <p><b>★★ 每个值都做了 URL 编码（percent-encoding），这不是可选项：</b>
     * HTTP 头的值按规范是 ISO-8859-1，而本项目里昵称、头像路径全是中文
     * （这个系统就是中文的）。不编码的后果是：
     * <pre>
     *   网关写 "墨白"  →  Netty 按 ISO-8859-1 出字节  →  下游按 ISO-8859-1 读
     *   → 得到一个乱码字符串（形如 "å¢¨ç™½"），而且【不报错】
     * </pre>
     * 编码之后所有值都是 ASCII，两头都安全。因为编码和解码在同一个类里
     * （{@link #read}），不存在「一边编了另一边没解」的可能。
     *
     * <p>★ 用 {@code URLEncoder} 而不是 Base64：Base64 编出来的值肉眼不可读，
     * 用 {@code curl -v} 或看网关日志排错时就失去了「一眼看出这是谁」的能力。
     * URL 编码保留了可读性（中文之外的部分原样保留）。
     */
    public static void write(Map<String, String> target, LoginUser user) {
        if (user == null) {
            return;
        }
        put(target, USER_ID, user.getUserId() == null ? null : String.valueOf(user.getUserId()));
        put(target, USERNAME, user.getUsername());
        put(target, NICKNAME, user.getNickname());
        put(target, AVATAR, user.getAvatar());
        put(target, JTI, user.getJti());

        /*
         * ★★ 角色和权限【必须】走 putList，不能走 put(join(...)) ——
         *    这是一个实测踩出来的、会造成「整个作者端 403」的坑：
         *
         *      put 会把【整个字符串】做 URL 编码，而 URLEncoder 把逗号编成 %2C。
         *      于是 "READER,AUTHOR" 变成 "READER%2CAUTHOR" 发出去了。
         *      读的那一端（split）是【先按 , 切、再逐个解码】——
         *      而这时候字符串里已经一个逗号都没有了（只剩 %2C），
         *      切出来就是【一个】元素 "READER,AUTHOR"，
         *      解码之后又变回长得一模一样的字符串。
         *
         *    ★ 症状极具迷惑性：文本【完全正确】，只是元素的【个数】错了。
         *      buildAuthorities 于是拼出唯一一条权限 "ROLE_READER,AUTHOR"，
         *      它和 hasRole("AUTHOR") 永远匹配不上。
         *
         *      而种子账号 1~4 恰好【全是单角色】（ADMIN / READER / READER / AUTHOR），
         *      单元素时切不切都一样 —— 所以这个坑在种子数据上 100% 不可见。
         *      它只在「审核通过之后的真实作者」身上发作：
         *      AuthorApplyServiceImpl 给的是【追加】AUTHOR，于是那个人是
         *      READER,AUTHOR 双角色 → 一被批准就再也进不去作者端，
         *      症状是「申请通过了，但作者中心打不开」。
         *
         *    ★ 修法是让两端的形状对上：列表【逐元素】编码，用【字面逗号】连接
         *      （逗号是合法的 HTTP 头字符，本来就该当分隔符用，不该被编码）。
         *      这样和 split 的「先切后解」严格对称。
         */
        putList(target, ROLES, user.getRoles());
        putList(target, PERMISSIONS, user.getPermissions());
    }

    /**
     * 写共享密钥头。
     *
     * <p>★ 单独一个方法而不是塞进 {@link #write}：那个方法收的是
     * {@link LoginUser}，而密钥和用户无关 —— 匿名请求（没登录的首页）
     * 也照样要带密钥，否则网关转发过去的请求会被下游当成伪造的。
     * <p>这一段是决策 4 里「两处都加共享密钥头」的第一处。
     */
    public static void writeInternalToken(Map<String, String> target, String token) {
        put(target, INTERNAL_TOKEN, token);
    }

    // ============================================================
    // 读：请求头 → LoginUser
    // ============================================================

    /**
     * 从一组头里还原登录态。
     *
     * <p><b>★★ 硬校验：{@link #USER_ID} 和 {@link #JTI} 缺任何一个都返回
     * {@code null}（= 匿名），而不是「能拼多少算多少」。</b>
     *
     * <p>为什么非要这么严：这两个字段是「必须有值」的，缺了说明
     * <b>网关和本服务的版本对不上</b>（网关升级漏了一个头，或者本服务
     * 比网关新）。如果在这里宽容处理，症状会退化成「部分功能静默异常」
     * —— 比如只有登出不好使（缺 JTI），或者只有写接口 500（缺 USER_ID）。
     * <p>硬校验之后，症状是<b>全站 401</b>：虽然难看，但它一眼可见、
     * 且立刻指向「头没传对」这唯一一个原因。调用方（过滤器）会补一条
     * {@code log.error} 把缺了哪个头点出来。
     *
     * <p>反过来说，{@code USERNAME} / {@code NICKNAME} / {@code AVATAR}
     * 是<b>可以缺</b>的：用户本来就可能没设昵称和头像，缺了只是显示为 null，
     * 不是错误。<b>「必须有值」和「可以为空」的区别，就是这个方法里
     * 硬校验列表的全部依据。</b>
     *
     * @param header 取头的方法。用 {@code Function} 而不是直接收
     *               {@code HttpServletRequest}，是为了让 WebFlux 的网关
     *               也能用同一个类（那边是 {@code ServerHttpRequest}，
     *               没有 servlet 的 API）。
     * @return 还原出来的登录态；身份头不全或格式不对时返回 {@code null}
     */
    public static LoginUser read(Function<String, String> header) {
        Long userId = parseLong(header.apply(USER_ID));
        String jti = decode(header.apply(JTI));
        if (userId == null || jti == null || jti.isEmpty()) {
            return null;
        }

        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setJti(jti);
        user.setUsername(decode(header.apply(USERNAME)));
        user.setNickname(decode(header.apply(NICKNAME)));
        user.setAvatar(decode(header.apply(AVATAR)));
        user.setRoles(split(header.apply(ROLES)));
        user.setPermissions(split(header.apply(PERMISSIONS)));
        return user;
    }

    // ============================================================
    // 剥：转发前把客户端可能自带的身份头全删掉
    // ============================================================

    /**
     * 把 {@link #ALL} 里的每一个头都交给 {@code remover} 删掉。
     *
     * <p><b>★★ 这一步必须在「写」之前做，顺序不能反过来。</b>
     * 反过来的话，客户端的头会把网关刚写的覆盖掉（或者两个值同时存在，
     * 下游取到哪个取决于实现）—— 那就是「任何人加一个
     * {@code -H "X-User-Id: 1"} 都是管理员」。
     *
     * <p>★ 收 {@code Consumer} 而不是具体类型：网关那边要删的是
     * {@code ServerHttpRequest} 的可变头（WebFlux），Feign 那边是
     * {@code RequestTemplate} 的头（它本来就是我们自己造的，不用剥）。
     * 一个方法两边都能用。
     */
    public static void strip(Consumer<String> remover) {
        ALL.forEach(remover);
    }

    // ============================================================
    // 共享密钥比对
    // ============================================================

    /**
     * 常量时间比较共享密钥。
     *
     * <p><b>★ 为什么不用 {@code equals}：</b>{@code String.equals} 在发现第一个
     * 不同的字符时就返回了，于是「比较耗时」和「猜对了前几个字符」相关 ——
     * 攻击者可以用计时差把密钥一个字符一个字符试出来。这是理论上的攻击
     * （网络抖动比这个差值大得多），但 {@code MessageDigest.isEqual}
     * 是 JDK 自带的、写起来一样短的常量时间实现，没有理由不用它。
     *
     * <p>★ 两边都先转成 UTF-8 字节再比：直接比 {@code char[]} 也可以，
     * 但字节比较顺带把「长度不同立刻返回」这个分支也吃掉了。
     *
     * @param provided 请求里带来的值，可以为 {@code null}
     * @param expected 配置里的正确值
     * @return 两者相等返回 {@code true}；{@code provided} 为空、
     *         或 {@code expected} 没配（为空）时返回 {@code false}
     */
    public static boolean tokenMatches(String provided, String expected) {
        /*
         * ★ expected 为空时必须返回 false，不能返回 true。
         *   "没配密钥就放行" 是最糟的一种失败方式：本机跑得好好的，
         *   部署到没配环境变量的环境上，/internal/** 就对全世界开放了，
         *   而且没有任何日志。宁可它一直 403（一眼可见）。
         */
        if (provided == null || expected == null || expected.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    // ============================================================
    // 内部小工具
    // ============================================================

    private static void put(Map<String, String> target, String name, String value) {
        if (value != null && !value.isEmpty()) {
            target.put(name, encode(value));
        }
    }

    /**
     * 写一个「多值」头：<b>逐元素编码，用字面逗号连接</b>。
     *
     * <p>★ 它和 {@link #put} 的区别只有一个，但那个区别是致命的：
     * {@code put} 编码的是<b>整个</b>字符串（连分隔符一起编），
     * 这个方法编码的是<b>每个元素</b>（分隔符原样保留）。
     * <p>★ 必须和 {@link #split}（先按 {@code ,} 切、再逐个解码）严格对称 ——
     * 改这一头就必须改那一头，否则就是上面 {@link #write} 注释里那个
     * 「元素个数错了」的坑。
     * <p>★ 分隔符用返回 {@code null} 而不是空串来表示「没有值」，
     * 和 {@link #put} 的「空值不写头」保持一致：下游 {@link #split}
     * 拿到 {@code null} 会返回空 List。
     */
    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(LIST_SEPARATOR);
            }
            sb.append(encode(value));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void putList(Map<String, String> target, String name, List<String> values) {
        String joined = join(values);
        if (joined != null) {
            target.put(name, joined);
        }
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isEmpty()) {
            /*
             * ★ 返回空 List 而不是 null：LoginUser 的字段初始化就是
             *   Collections.emptyList()，下游 buildAuthorities 也直接
             *   for-each。给 null 会在一片「都能跑」的代码里埋一个 NPE。
             */
            return Collections.emptyList();
        }
        /*
         * ★★ 顺序是【先切、后解码】，和 join 的【先编码、后连接】严格对称。
         *    反过来（先解码整个串再切）在这里恰好也能work，但那样
         *    「值里本来就有个逗号」和「分隔符」就分不清了 ——
         *    宁可让两个方法都保持「分隔符永远没被编码过」这个前提，
         *    它是一条能一眼验证的不变量。
         */
        List<String> result = new ArrayList<>();
        for (String part : raw.split(LIST_SEPARATOR)) {
            String decoded = decode(part);
            if (decoded != null && !decoded.isEmpty()) {
                result.add(decoded);
            }
        }
        return result;
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            /*
             * ★ 不抛异常：格式不对就是「这个头不可信」，按匿名处理。
             *   抛出去会变成 500，而 500 在这里是错的答案 ——
             *   正确的答案是 401（由下游的 RestAuthenticationEntryPoint 出）。
             */
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            /*
             * ★ 解不开（比如值里有个孤立的 %）时原样返回，不抛异常。
             *   这个值可能压根不是我们编码的（有人手工构造了请求），
             *   原样返回让它作为一个「看着奇怪但无害」的字符串继续走，
             *   最终由日志暴露出来。
             */
            return value;
        }
    }

    /**
     * ★ 给排错用：把一组头按「哪些是身份头」的顺序打出来。
     *
     * <p>注意<b>会把共享密钥打码</b> —— 它和 JWT 一样属于「进了日志就等于泄露」
     * 的东西（日志的访问权限通常比配置文件松）。
     */
    public static String describe(Function<String, String> header) {
        StringBuilder sb = new StringBuilder();
        for (String name : ALL) {
            String value = header.apply(name);
            if (value == null) {
                continue;
            }
            if (INTERNAL_TOKEN.equals(name)) {
                value = "***";
            }
            sb.append(name).append('=').append(value).append(' ');
        }
        return sb.length() == 0 ? "(无身份头)" : sb.toString().trim();
    }

    /** ★ 供单测断言「ALL 覆盖了所有常量」用，避免以后加了常量忘了进 ALL。 */
    public static List<String> allNames() {
        return Arrays.asList(USER_ID, USERNAME, NICKNAME, AVATAR, JTI, ROLES, PERMISSIONS, INTERNAL_TOKEN);
    }
}
