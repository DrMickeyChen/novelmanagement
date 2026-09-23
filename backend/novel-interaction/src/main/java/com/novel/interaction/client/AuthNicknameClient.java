package com.novel.interaction.client;

import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * <b>interaction 侧调 novel-auth 的 Feign 客户端（M2）—— 只为了取昵称。</b>
 *
 * <h3>★★ 为什么只有一个方法（那边有四个）</h3>
 *
 * <p>{@code AuthUserClient}（content 侧那份）有四个方法，因为它要
 * ①取单个昵称 ②批量取昵称 ③按昵称反查用户 ④取作者申请摘要。
 * interaction 只用到<b>批量取昵称</b>这一个 —— 评论列表要渲染「谁说的」。
 *
 * <p><b>★ 铁律 1 的 M1 版表述：接口住在消费方，消费方只声明自己用得到的。</b>
 * 把那个四方法客户端提到 {@code novel-common} 共用是<b>错的</b>，
 * 有两个独立的理由：
 * <ol>
 *   <li>{@code novel-auth} 的启动类也在 {@code com.novel}，
 *       {@code @EnableFeignClients} 会把这个共用接口<b>也注册进 auth 自己</b>
 *       —— 那是一个指向自己的 Feign 客户端，永远不会有调用，
 *       正是铁律 1 点名的「能编译、能被扫描、永远不会被调用的死 bean」。</li>
 *   <li>更直接：<b>声明四个用不上的方法，就是四个能被用错的入口。</b>
 *       将来 interaction 里有人顺手调了「按昵称反查用户」，
 *       它会成功、会返回数据，而那次调用<b>从来没人设计过。</b></li>
 * </ol>
 *
 * <h3>★ 为什么读昵称要过一次 HTTP，而不是本地查</h3>
 *
 * <p>{@code sys_user} 表在 novel-auth 那边（M1 摘出去的）。
 * 这是「数据在谁那儿，查询就归谁写」的直接结果。
 * <p>★ 更往上追一层：改造前 interaction 是通过 content 的
 * {@code ContentUserLookup} 间接拿昵称的（content → system 那条接缝）。
 * M2 之后 interaction 和 content 分了进程，<b>为一串昵称绕道 content
 * 意味着多一跳</b>（interaction → content → auth），
 * 而 auth 本来就愿意直接回答这个问题（{@code InternalUserController}
 * 在 M1 就开了这个口子）。所以就近直连，少一跳。
 *
 * <p>★ 和 content 那条边不同，这条是<b>新开的口子</b>：
 * interaction 在 M2 之前从没直接调过 auth。它不改依赖方向
 * （{@code interaction → system} 和 {@code content → system} 一样，
 * 都是「业务模块读基础数据」），只是多了一个消费者。
 *
 * @author MickeyChen
 */
/* ★★ M5：fallbackFactory 的两个必要条件（这里 + feign.sentinel.enabled），见 AuthUserClientFallbackFactory */
@FeignClient(name = "novel-auth", path = "/internal",
        fallbackFactory = AuthNicknameClientFallbackFactory.class)
public interface AuthNicknameClient {

    /**
     * 批量取昵称，返回 {@code {userId: nickname}}。
     *
     * <p>★ 用 {@code POST} + body：一页评论 20 条可能对应 20 个用户
     * （去重后），塞进 query string 会撞上 URL 长度限制，
     * 而那个限制在不同服务器上不一样（症状是「评论多了就报 400」，
     * 本地还复现不了）。镜像 {@code InternalUserController} 的既有范式。
     *
     * <p>★ <b>调用方必须先判空集合</b>（对端虽然也判了，但这里判
     * 省的是这次往返本身）。见 {@code CommentServiceImpl#nicknamesOf}。
     * <p>★ 用户不存在 / 已注销时那个 id 不会出现在 Map 里 ——
     * 调用方按 {@code null} 处理（显示「已注销用户」，而不是把这行藏掉）。
     */
    @PostMapping("/users/nicknames")
    Result<Map<Long, String>> nicknamesOf(@RequestBody List<Long> userIds);
}
