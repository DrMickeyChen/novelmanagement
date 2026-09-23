package com.novel.modules.content.client;

import com.novel.common.internal.AuthorApplyBriefDTO;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * <b>content 侧调 novel-auth 的 Feign 客户端（M1）。</b>
 *
 * <p><b>★★ 这个接口为什么住在【消费方】这一侧，而不是和它的实现放在一起：</b>
 *
 * <p>铁律 1 说「跨模块读数据只能走接缝，接口定义在<b>消费方</b>那一侧」。
 * 改造前那句规矩的形态是：接口在 {@code modules/content}、
 * 实现（直接查库）也在 {@code modules/content}，靠同进程 DI 注入。
 *
 * <p>换成 Feign 之后，「实现」的内容变成了「<b>发一次 HTTP 请求</b>」——
 * 而这件事<b>只能住在调用发生的地方</b>。如果把它挪到 auth 那边，
 * novel-content 拿到的就是一个「能编译、能被扫描、但永远不会被调用」的
 * 死 bean，而且没有任何报错。
 *
 * <p>所以规矩在 M1 之后要重新表述为：
 * <blockquote>
 * <b>业务语义的接口住在消费方；提供方只暴露 URL，不暴露类型。</b>
 * </blockquote>
 * 这个接口里出现的 {@link Result} 和 {@link AuthorApplyBriefDTO} 都在
 * {@code novel-common}（两边都编译得到），而 novel-auth 那边
 * <b>根本不知道这个接口存在</b> —— 它只是恰好有一个路径相同的
 * {@code @RestController}。
 *
 * <p><b>★ {@code name = "novel-auth"} 是怎么解析成地址的：</b>
 * 交给 Nacos + Spring Cloud LoadBalancer（{@code lb://}），
 * 所以 novel-content 的 pom 里<b>必须有
 * {@code spring-cloud-starter-loadbalancer}</b> ——
 * 缺了会在第一次调用时报
 * {@code No Feign Client for loadBalancing defined}，
 * 报错完全看不出和「少一个依赖」有关。
 *
 * <p><b>★★ 这个客户端的四个方法【都不是】通用的 CRUD，而是四个具体的业务问题。</b>
 * 这是有意维持的：内部接口一旦变成「万能查询」，它就成了一个绕过服务边界的
 * 后门（那样还不如不拆）。加方法之前先问一句「这个问题是 auth 该回答的吗」。
 *
 * @author MickeyChen
 */
/*
 * ★★ M5：fallbackFactory 是【同时】要满足两个条件的，缺一个就是一段死代码：
 *   ① 这里写上它；② application.yml 里 feign.sentinel.enabled: true。
 *   少了 ②，SCA 走 DefaultTargeter，这个工厂永远不会被创建，
 *   而应用正常启动、零报错 —— 详细论证在 AuthUserClientFallbackFactory 的注释里。
 */
@FeignClient(name = "novel-auth", path = "/internal",
        fallbackFactory = AuthUserClientFallbackFactory.class)
public interface AuthUserClient {

    /** 单个用户的昵称。查不到返回 {@code data: null}（不是 404）。 */
    @GetMapping("/users/{id}/nickname")
    Result<String> nicknameOf(@PathVariable("id") Long id);

    /**
     * 批量取昵称，返回 {@code {userId: nickname}}。
     *
     * <p>★ 用 POST 而不是 GET：id 列表可能到 100 个（搜索页），
     * 塞进 query string 会撞上 URL 长度限制，而那个限制在不同服务器上不一样。
     */
    @PostMapping("/users/nicknames")
    Result<Map<Long, String>> nicknamesOf(@RequestBody List<Long> userIds);

    /** 反查：昵称关键词 → 用户 id 列表。给「按作者名搜索作品」用。 */
    @GetMapping("/users/ids-by-nickname")
    Result<List<Long>> userIdsByNickname(@RequestParam("keyword") String keyword);

    /**
     * 批量取作者申请的最少信息（id / 申请人 / 笔名）。
     *
     * <p>★ 为什么需要它、以及为什么必须同时拿到两个 id，见
     * {@code ContentUserLookup#authorApplyTitlesOf} 的注释 ——
     * 那里记着一个「不报错但显示成别人的申请」的坑。
     */
    @PostMapping("/author-applies/briefs")
    Result<List<AuthorApplyBriefDTO>> briefsOf(@RequestBody List<Long> applyIds);
}
