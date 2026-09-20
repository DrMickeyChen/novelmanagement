package com.novel.common.security;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;

/**
 * 当前登录用户的线程上下文 —— 大纲 §696 定下的约定：
 * <b>「由 JWT 拦截器写入，Service 直接取」</b>。
 *
 * <p><b>它解决的问题：</b>Service 层经常需要知道「现在是谁在操作」——
 * 「只能删自己的评论」、「只能改自己的作品」。没有这个上下文的话，
 * 每一层方法都得把 userId 当参数往下传：
 * <pre>
 *   controller.delete(id, currentUserId)
 *     → service.delete(id, currentUserId)
 *       → helper.checkOwner(id, currentUserId)   ← 每一层都得记着传
 * </pre>
 * 漏传一处就变成「谁都能删」，而且这种 bug 不会报错，只会安静地越权。
 * 有了 UserContext，Service 里直接 {@code UserContext.getUserId()} 就行。
 *
 * <p><b>★★ ThreadLocal 最大的坑：用完必须 remove。</b>
 * Tomcat 用线程池处理请求 —— 一个线程处理完 A 的请求后<b>不会销毁</b>，
 * 而是被复用去处理 B 的请求。如果不清，B 的请求读到的还是 A 的身份，
 * 也就是「张三登录着，李四的请求却以张三的身份执行」。这类串号问题
 * 极难复现（本地压测时很容易碰不到），而且一旦发生就是严重的越权。
 * 所以 {@link filter.JwtAuthenticationFilter} 在 {@code finally} 里无条件调用
 * {@link #clear()}。
 *
 * <p><b>为什么是静态方法而不是 Spring Bean：</b>
 * ThreadLocal 本身就得是静态的（每个线程一份，跟谁注入无关）。
 * 做成 Bean 反而要求每个使用点都注入一次，纯属噪音。
 *
 * <p><b>第二期怎么办：</b>认证搬到网关后，网关校验完 JWT 会把
 * {@code X-User-Id} / {@code X-User-Role} 写进请求头（见
 * {@code CommonConstant.HEADER_USER_ID}），服务侧改成从请求头填充本类，
 * <b>下面的所有用法一行都不用改</b>。
 *
 * @author MickeyChen
 */
public final class UserContext {

    private UserContext() {
    }

    /**
     * ★ 存的是 {@link LoginUser} 而不是只存 userId。
     * 因为判权限时要看角色/权限，只存一个 id 的话每次还得回查一遍库。
     */
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 由拦截器在认证成功后调用 */
    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    /** 取当前用户，未登录时返回 {@code null}（匿名接口里是正常的） */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 取当前用户 ID，未登录时返回 {@code null} */
    public static Long getUserId() {
        LoginUser loginUser = HOLDER.get();
        return loginUser == null ? null : loginUser.getUserId();
    }

    /** 取当前用户名，未登录时返回 {@code null} */
    public static String getUsername() {
        LoginUser loginUser = HOLDER.get();
        return loginUser == null ? null : loginUser.getUsername();
    }

    /**
     * 取当前用户 ID，<b>未登录直接抛业务异常</b>。
     *
     * <p>用在「这个接口没登录根本不该走到这里」的地方。
     * 正常流程下请求已经被安全链拦住了，走不到这儿 ——
     * 所以它真正的作用是<b>兜底</b>：万一哪天有人把接口加进白名单
     * 却忘了加 {@code @PreAuthorize}，这里会抛错而不是
     * 拿着 {@code null} 去查库（那会变成「查 userId 为 null 的记录」，
     * 结果要么是空，要么更糟）。
     */
    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /** 当前用户是否拥有某个角色。传<b>裸角色码</b>（{@code "ADMIN"}），不用带 {@code ROLE_} 前缀 */
    public static boolean hasRole(String roleCode) {
        LoginUser loginUser = HOLDER.get();
        return loginUser != null
                && loginUser.getRoles() != null
                && loginUser.getRoles().contains(roleCode);
    }

    /** 当前用户是否拥有某个权限，如 {@code content:novel:list} */
    public static boolean hasPermission(String perm) {
        LoginUser loginUser = HOLDER.get();
        return loginUser != null
                && loginUser.getPermissions() != null
                && loginUser.getPermissions().contains(perm);
    }

    /**
     * ★★ 清理，必须在拦截器的 {@code finally} 里调用。
     *
     * <p>不调用的后果见类注释：线程池复用会让下一个请求读到上一个用户的身份。
     * 这不是「可能有点小问题」，而是一个确定的越权漏洞。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
