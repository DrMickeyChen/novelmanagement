package com.novel.modules.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.modules.ai.enums.AiFailure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议的 HTTP 客户端（E1）—— <b>本模块唯一与外部世界说话的地方</b>。
 *
 * <h3>★★★ 为什么用 JDK 自带的 {@code java.net.http.HttpClient}，而不是 WebClient</h3>
 *
 * <p>项目大纲 §8.4 写的是 WebClient。<b>E1 改成 JDK HttpClient，理由有两条：</b>
 * <ol>
 *   <li><b>零新依赖。</b>{@code WebClient} 属于 Spring WebFlux，引它等于把一个
 *       响应式运行时搬进一个纯 MVC 的应用。{@code AiStreamSource} 的类注释在 P4
 *       就写下了这条反对意见（「引它等于把整个 web 栈换掉，代价远超收益」）。
 *       ★ 验证表第 4 条是它的判据：{@code unzip -l novel-content.jar | grep -i webflux}
 *       必须是空的 —— <b>看构件，不看 pom</b>（铁律 8：判据要钉在结构上）。</li>
 *   <li><b>形状天然对得上。</b>{@code BodyHandlers.ofLines()} 给的就是
 *       「一行一行地推过来」，而 SSE 的线格式<b>本来就是一行一帧</b>；
 *       下游的 {@code AiStreamSource.Handler} 也是一个「给一段就处理一段」的回调。
 *       ★ 接口注释自己写的：「真实模型的 HTTP 客户端本来就是回调/逐行读的」。</li>
 * </ol>
 *
 * <h3>★★★ 三条绝不出门的纪律（安全，不是洁癖）</h3>
 *
 * <ol>
 *   <li><b>不跟随重定向</b>（{@link HttpClient.Redirect#NEVER}）。
 *       {@code Authorization} 头是<b>跟着重定向走的</b> —— 一个配错的
 *       {@code base_url} 只要返回 302，我们就会把明文 Key 交给另一个主机。
 *       ★ 而返回 302 这件事本身没有任何症状，它看起来就是「调用成功了」。</li>
 *   <li><b>非 200 时【不读响应体】</b>。真实 DeepSeek 的 401 body 里带着
 *       {@code "Your api key is: sk-xxx..."} —— 读了就有机会被写进日志或数据库。
 *       所以我们只看状态码。<b>代价是排查 400 时看不到原因，这是刻意的取舍</b>：
 *       要看原因就用一把一次性 Key 手工 curl，而不是让生产日志有泄漏的可能。</li>
 *   <li><b>异常消息里不放上游内容</b>。{@link UpstreamException#getMessage()}
 *       是自己拼的（失败码 + 状态码），<b>不是上游的原文</b>。
 *       ★ 它会被 {@code AiController} 记进 error 日志，也就是会进
 *       {@code backend/logs/novel-content.log} 那个文件。</li>
 * </ol>
 *
 * <h3>★★ 一个诚实的局限（没有解决，记在案）</h3>
 *
 * <p>JDK 的 {@code HttpClient} <b>没有响应体读取超时</b>：
 * {@code HttpRequest.timeout} 管的是「响应头什么时候到」，
 * 而流已经开始之后，一个卡住不动的上游会让读线程<b>永远阻塞</b>。
 *
 * <p>后果是：SSE 那一侧会按时超时（{@code AiController} 的 3 分钟），
 * 用户看到流断了，但 {@code ai-stream-N} 这个工作线程还挂在那儿 ——
 * 池子上限是 4，所以理论上连着 4 次这样的上游可以把这个功能占满。
 * ★ 要真解决需要在读循环上加一个看门狗（或者换成带 body 超时的客户端），
 * 那属于「多引入一个依赖 + 一段并发代码」，E1 不做，<b>但明确写在这里</b> ——
 * 一个已知的、写下来的局限，和一个没想到的局限，是两回事。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
public class AiModelClient {

    /** 建连超时。★ 配错的 {@code base_url}（域名不存在 / 端口不通）在这里就失败了 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 响应头超时。
     *
     * <p>★ <b>不是「整个请求」的超时</b> —— 流式响应开始之后就不再受它约束。
     * 见类注释里那条诚实的局限。
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    private static final String DONE = "[DONE]";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                /*
                 * ★★★ NEVER 是安全设置，不是保守设置 —— 见类注释纪律 1。
                 *   有人把它改成 NORMAL「顺手支持一下跳转」的话，
                 *   症状是 Key 被发到一个我们没配过的主机上，而日志上一切正常。
                 */
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /* ==================== 对外两种调用 ==================== */

    /**
     * 流式调用：<b>每收到一段增量就回调一次</b>，阻塞到流结束。
     *
     * <p>★ <b>本方法是阻塞的</b>，调用方必须自己在单独线程上跑
     * （流式路径上是 {@code ai-stream-N}，见 {@code AiController}）。
     *
     * <p>★★ <b>关于 {@code onDelta} 抛异常：不接、不包、原样往上冲。</b>
     * 那是 {@code AiStreamSource} 契约里的第二条 ——
     * 调用方在客户端断开后抛出的中断信号必须能<b>冲掉读取循环</b>，
     * 否则「用户点了中断，服务端停止烧 token」就是假的。
     * 这里的写法是：{@code onDelta} 的调用<b>不在任何 try 里面</b>。
     *
     * @param onDelta 收到增量文本（<b>增量不是全量</b>，且不保证一次一个字符）
     * @return 这次调用的 token 消耗；上游没给 usage 时是 {@link AiChatUsage#ZERO}
     * @throws UpstreamException 一切失败（连接、状态码、解析）都走这一个类型
     */
    public AiChatUsage streamChat(AiRuntimeConfig cfg, String apiKey, String systemPrompt,
                                  String userPrompt, Consumer<String> onDelta) {
        ObjectNode body = buildBody(cfg, systemPrompt, userPrompt, true);
        HttpResponse<Stream<String>> response = send(
                request(cfg, apiKey, body, true), HttpResponse.BodyHandlers.ofLines());
        requireOk(response.statusCode(), response.body()::close);

        AiChatUsage usage = AiChatUsage.ZERO;
        boolean sawDone = false;
        int chunks = 0;
        long start = System.currentTimeMillis();

        /*
         * ★★ try-with-resources 是【中断机制的一部分】，不是资源洁癖：
         *   onDelta 抛出的中断异常会从循环里冲出去，此时 close() 关掉的是
         *   【到上游的那条 TCP 连接】—— 假端点那一侧就是在这一刻看到
         *   CLIENT-DISCONNECT 的。不加 try-with-resources 的话，
         *   「服务端停止烧 token」只做到了「不再读」，连接还开着。
         */
        try (Stream<String> lines = response.body()) {
            Iterator<String> it = lines.iterator();
            while (true) {
                String line;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    line = it.next();
                } catch (UncheckedIOException e) {
                    /*
                     * ★ chunked 编码被中途掐断（上游崩了 / 代理断了）在这里现形。
                     *   ★ 它和 INCOMPLETE_STREAM 是两件事：那里是「干净地结束了但没给 [DONE]」，
                     *     这里是「压根没结束就断了」。分开记，因为排查方向不同。
                     */
                    throw new UpstreamException(AiFailure.STREAM_BROKEN, 0, e);
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == ':') {
                    continue;                       // 空行是帧分隔符；冒号开头是 SSE 注释/心跳
                }
                if (!trimmed.startsWith("data:")) {
                    continue;                       // event: / id: / retry: —— OpenAI 协议里用不到
                }
                String payload = trimmed.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if (DONE.equals(payload)) {
                    sawDone = true;
                    break;
                }

                JsonNode frame = readTree(payload);
                if (frame == null || !frame.isObject()) {
                    continue;
                }

                /*
                 * ★★★ 这两段【顺序不能换】—— 它是判断 4 那个「静默为 0」陷阱的解法。
                 *
                 *   带 usage 的那一帧，`choices` 是【空数组】（真实的 DeepSeek 就是这么发的）。
                 *   而最自然的防御性写法是：
                 *       if (choices 为空) continue;      ← 为了躲开 choices[0] 的越界
                 *       ...再读 usage...
                 *   那样 usage 会【和空 choices 一起被跳过】→ 库里 token 全是 0，且不报错。
                 *
                 *   ★ 所以规则的写法是：**先无条件地读 usage，再判 choices**。
                 *   ★ 判据：`ai_usage_log` 里 token 全 0 和「模型没返回 usage」
                 *     长得一模一样 —— 只有一次真实调用能证明它（预算表 R1）。
                 */
                JsonNode usageNode = frame.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    usage = readUsage(usageNode);
                    // ★ 不 break：usage 帧后面还有一帧 finish_reason 和一帧 [DONE]
                }

                JsonNode error = frame.get("error");
                if (error != null && !error.isNull()) {
                    /*
                     * ★★ 只记 error.type（一个短枚举值），【绝不记 error.message】——
                     *   某些网关会把 invalid api key 连同 Key 片段放进 message。
                     *   见 AiFailure#UPSTREAM_ERROR。
                     */
                    String type = error.path("type").asText("");
                    log.warn("上游在响应流里报告了错误 | model={} | errorType={}", cfg.model(), type);
                    throw new UpstreamException(AiFailure.UPSTREAM_ERROR, 0, null);
                }

                JsonNode choices = frame.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    // ★ 这里 continue 是【安全的】，因为 usage 上面已经读过了
                    continue;
                }
                JsonNode content = choices.get(0).path("delta").get("content");
                if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                    chunks++;
                    onDelta.accept(content.asText());
                }
            }
        }

        if (!sawDone) {
            /*
             * ★★ 「干净地结束了」和「完成了」不是一回事。
             *   上游按 chunked 正常收尾、我们读到 EOF，但【没有 [DONE]】——
             *   那说明这次响应被截断了（网关限流、上游超时、代理改写）。
             *   ★ 不把这种半截内容当成成功交付给用户：
             *     它看起来完全正常（有内容、也没报错），只是<b>后面少了一半</b>。
             */
            throw new UpstreamException(AiFailure.INCOMPLETE_STREAM, 0, null);
        }
        if (chunks == 0) {
            throw new UpstreamException(AiFailure.EMPTY_RESPONSE, 0, null);
        }

        log.info("上游流式调用完成 | model={} | 耗时={}ms | 增量帧={} | tokens={}/{}/{}",
                cfg.model(), System.currentTimeMillis() - start, chunks,
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
        return usage;
    }

    /**
     * 非流式调用：一次拿全（<b>审核场景用</b>）。
     *
     * <p>★ 审核为什么不用流：见 {@code AiScene#AUDIT} 的注释 ——
     * {@code AiStreamSource.Handler} 的契约是为 SSE 连接的断开语义服务的，
     * 而审核没有连接可断。且非流式一次给全 usage，更少出错。
     */
    public ChatResult chat(AiRuntimeConfig cfg, String apiKey, String systemPrompt, String userPrompt) {
        ObjectNode body = buildBody(cfg, systemPrompt, userPrompt, false);
        /*
         * ★★ 用 ofInputStream 而不是 ofString —— 这是纪律 2 能成立的关键。
         *   ofString 会【在读之前就把整个响应体读进内存】，
         *   于是「非 200 时不读响应体」这条规矩在非流式路径上就是一句空话：
         *   真实的 DeepSeek 401 body 里带着 Key 片段，而它已经躺在堆里了。
         *   ★ 换成 InputStream 之后，状态码先到、body 后读，我们才真的有机会
         *     在「看到 401」和「读到那段文字」之间做选择。
         */
        HttpResponse<InputStream> response = send(
                request(cfg, apiKey, body, false), HttpResponse.BodyHandlers.ofInputStream());
        requireOk(response.statusCode(), response.body());

        String raw;
        try (InputStream in = response.body()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UpstreamException(AiFailure.STREAM_BROKEN, 0, e);
        }

        JsonNode root = readTree(raw);
        JsonNode choices = root == null ? null : root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new UpstreamException(AiFailure.EMPTY_RESPONSE, 0, null);
        }
        String content = choices.get(0).path("message").path("content").asText("");
        JsonNode usageNode = root.get("usage");
        AiChatUsage usage = usageNode != null && usageNode.isObject() ? readUsage(usageNode) : AiChatUsage.ZERO;
        return new ChatResult(content, usage);
    }

    /* ==================== 内部 ==================== */

    /** 非流式调用的结果 */
    public record ChatResult(String content, AiChatUsage usage) {
    }

    /**
     * 上游调用失败。<b>本模块对外只有一个失败类型</b>，调用方 catch 一次就够。
     *
     * <p>★★★ {@link #getMessage()} 是<b>拼出来的</b>，不是上游给的：
     * 它只有失败码和 HTTP 状态码。这不是为了好看 ——
     * 这个方法的结果会进 error 日志，而上游的原文里可能有 Key 片段（见类注释纪律 3）。
     */
    public static class UpstreamException extends RuntimeException {

        private final AiFailure failure;
        private final int status;

        UpstreamException(AiFailure failure, int status, Throwable cause) {
            super(failure.getCode() + (status > 0 ? " (HTTP " + status + ")" : ""), cause);
            this.failure = failure;
            this.status = status;
        }

        public AiFailure failure() {
            return failure;
        }

        /** HTTP 状态码；{@code 0} 表示还没走到 HTTP 那一层 */
        public int status() {
            return status;
        }
    }

    private HttpRequest request(AiRuntimeConfig cfg, String apiKey, ObjectNode body, boolean stream) {
        return HttpRequest.newBuilder(endpoint(cfg))
                .timeout(RESPONSE_TIMEOUT)
                .header("Content-Type", "application/json")
                /*
                 * ★ Key 只在这里出现一次，且只进请求头。
                 *   ★ 它【绝不】进 URL：URL 会出现在访问日志、代理日志、
                 *     HttpClient 自己的异常信息里 —— 而项目里那条
                 *     「token 绝不能出现在 URL 中」的规矩（sse.js 用 fetch 而不是
                 *     EventSource 就是为了它）在这里是同一条。
                 */
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 端点 URL。
     *
     * <p>★ {@code base_url} 里已经带了 {@code /v1}（默认值是
     * {@code https://api.deepseek.com/v1}），所以这里只拼 {@code /chat/completions}。
     * ★ 末尾斜杠要处理掉，否则拼出来是 {@code //chat/completions} ——
     * 多数网关能容忍，但有的会 404，而 404 的排查方向是「路径配错了」，
     * 与真实的「多了个斜杠」差一层。
     */
    private static URI endpoint(AiRuntimeConfig cfg) {
        String base = cfg.baseUrl() == null ? "" : cfg.baseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new UpstreamException(AiFailure.CONNECT_FAILED, 0, null);
        }
        return URI.create(base + "/chat/completions");
    }

    private ObjectNode buildBody(AiRuntimeConfig cfg, String systemPrompt, String userPrompt, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", cfg.model());

        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", userPrompt == null ? "" : userPrompt);

        if (cfg.temperature() != null) {
            root.put("temperature", cfg.temperature());
        }
        if (cfg.maxTokens() != null) {
            /*
             * ★ max_tokens 是【花钱护栏】的直接落点（风险清单 R6）：
             *   没有它，一次调用能按模型允许的最大长度计费。
             *   管理端对这个字段有 1~8192 的上限校验（AiConfigSaveDTO）。
             */
            root.put("max_tokens", cfg.maxTokens());
        }
        root.put("stream", stream);
        if (stream) {
            /*
             * ★★★ `stream_options.include_usage = true` —— 少了它，
             *   流式响应【根本不会带 usage 帧】。
             *
             *   而它的症状正是判断 4 那个最难查的形状：`ai_usage_log` 里
             *   token 全是 0，<b>和「模型没返回 usage」长得一模一样</b>，
             *   而且不报错。★ 只有一次真实调用能把它和「上游没给」分开（预算表 R1）。
             */
            root.putObject("stream_options").put("include_usage", true);
        }
        return root;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        long start = System.currentTimeMillis();
        try {
            return httpClient.send(request, handler);
        } catch (HttpConnectTimeoutException e) {
            throw new UpstreamException(AiFailure.CONNECT_FAILED, 0, e);
        } catch (HttpTimeoutException e) {
            throw new UpstreamException(AiFailure.TIMEOUT, 0, e);
        } catch (InterruptedException e) {
            /*
             * ★ 恢复中断标志再抛 —— 吞掉 InterruptedException 而不恢复标志
             *   是 Java 并发里最经典的错误（同 MockAiStreamSource#sleepQuietly 的注释）。
             */
            Thread.currentThread().interrupt();
            throw new UpstreamException(AiFailure.CONNECT_FAILED, 0, e);
        } catch (IllegalArgumentException e) {
            // URI.create 失败（base_url 里有非法字符）
            throw new UpstreamException(AiFailure.CONNECT_FAILED, 0, e);
        } catch (java.io.IOException e) {
            throw new UpstreamException(AiFailure.CONNECT_FAILED, 0, e);
        } finally {
            /*
             * ★ 措辞要准：流式那条 BodyHandlers.ofLines() 是【惰性】的，
             *   send() 返回时只拿到了响应头，正文还在后面一点一点读。
             *   所以这里量的是「建连 + 响应头」的耗时，不是整次生成的耗时。
             *   （写错的话排查「模型好慢」时会看到一个漂亮的小数字。）
             */
            log.debug("上游已响应（建连+响应头）| 耗时={}ms", System.currentTimeMillis() - start);
        }
    }

    /**
     * 非 200 一律抛。
     *
     * <p>★★ <b>不读响应体</b>，只把连接关掉（{@code body} 传给 {@link AutoCloseable#close()}）——
     * 见类注释纪律 2：真实 DeepSeek 的 401 body 里带 Key 片段。
     *
     * <p>★ 关掉是有必要的，不是洁癖：不关的话这个连接不会归还给连接池，
     * 每条失败请求漏一条 —— 而失败的路径恰恰是「上游在抽风，请求会很多」的时候。
     */
    private void requireOk(int status, AutoCloseable body) {
        if (status == 200) {
            return;
        }
        if (body != null) {
            try {
                body.close();
            } catch (Exception ignored) {
                // 关不掉就算了：这是一个已经在失败路径上的收尾动作
            }
        }
        throw new UpstreamException(failureOf(status), status, null);
    }

    /**
     * 状态码 → 失败码。
     *
     * <p>★ 404 和 429 单独列出来，因为它们<b>指向的动作完全不同</b>：
     * 前者去改 {@code base_url}，后者什么都不用改、等一会儿就好。
     * 见 {@code AiFailure} 的类注释。
     */
    private static AiFailure failureOf(int status) {
        return switch (status) {
            case 401 -> AiFailure.UPSTREAM_401;
            case 403 -> AiFailure.UPSTREAM_403;
            case 404 -> AiFailure.UPSTREAM_404;
            case 429 -> AiFailure.UPSTREAM_429;
            default -> status >= 500 ? AiFailure.UPSTREAM_5XX : AiFailure.UPSTREAM_4XX;
        };
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            /*
             * ★ 异常本体带上了 [Source: (String)"…"] —— 也就是上游原文。
             *   它会进日志。这是【服务端日志】，不是数据库、不是前端、不是 error_msg，
             *   所以可以接受（要排查就得看得见）；但绝不能顺着 cause 往上爬进 error_msg。
             *   见 AiFailure 的类注释与 AiUsageRecorder#record 的落点。
             */
            throw new UpstreamException(AiFailure.PARSE_FAILED, 0, e);
        }
    }

    private static AiChatUsage readUsage(JsonNode node) {
        return new AiChatUsage(
                node.path("prompt_tokens").asInt(0),
                node.path("completion_tokens").asInt(0),
                node.path("total_tokens").asInt(0));
    }
}
