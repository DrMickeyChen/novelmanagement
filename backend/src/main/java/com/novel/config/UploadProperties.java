package com.novel.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件上传的配置（{@code novel.upload.*}）。
 *
 * <p><b>对应配置（在 {@code application-dev.yml} 里，不在基础 {@code application.yml}）：</b>
 * <pre>
 *   novel:
 *     upload:
 *       path: ${user.home}/novel-uploads/
 *       url-prefix: /uploads/
 * </pre>
 *
 * <p><b>★ 为什么这个类存在，而不是在用的两个地方各写一个 {@code @Value}：</b>
 * 因为「配置里的字符串」和「真正能用的东西」之间隔着几步加工，
 * 而那几步<b>每一处都踩过坑</b>：
 * <ul>
 *   <li>{@code ${user.home}/novel-uploads/} → 要变成绝对路径才能落盘
 *       （相对路径的基准是「进程的工作目录」，IDE 里跑是 {@code backend/}、
 *       {@code java -jar} 跑是当前 shell 目录，同一个配置会指向两个地方）</li>
 *   <li>目录路径 → 要变成 {@code file:} 开头的 URI 才能给静态资源映射用，
 *       ★ 而且<b>结尾必须有一个斜杠</b>，见 {@link #getResourceLocation()}</li>
 *   <li>前缀 {@code /uploads/} → 要保证首尾都有斜杠才能安全拼接，
 *       而 {@code "/uploads"} 和 {@code "/uploads/"} 拼出来的 URL 一个是
 *       {@code /uploadscover/x.jpg}（错的）、一个是对的</li>
 * </ul>
 * 这些加工各写一遍就会各错一遍，而且<b>错法都不一样</b>。放在一个类里，
 * 「怎么从配置算出路径和 URL」全项目只有一份答案。
 *
 * <p><b>★★ 上传目录【绝不能】放在 {@code backend/src/main/resources/static/} 下。</b>
 * 那里面的东西会被打进 jar 包，开发期（IDE 里跑、目录还在磁盘上）完全正常，
 * 一 {@code mvn package} 就变成「写入静默失败/写进了临时解压目录」——
 * 现象是<b>图片上传返回成功，但刷新页面就 404</b>，而且换台机器复现不了。
 * 所以这里用的是用户主目录下的绝对路径（{@code C:\Users\Asus\novel-uploads\}）。
 *
 * <p>★ 这个类不做任何「上传的内容」相关的校验 —— 白名单、魔数那些在
 * {@code AuthorUploadController} 里，因为它们和「文件存哪」无关。
 * 这里只管「存哪」和「怎么拼成 URL」。
 *
 * @author MickeyChen
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "novel.upload")
public class UploadProperties {

    /**
     * 上传根目录。开发期是 {@code ${user.home}/novel-uploads/}。
     *
     * <p>★ 没有默认值，配错了启动就失败（和 {@code novel.jwt.secret} 同一个策略）：
     * 给个默认值的话，生产环境忘了配就会静默往「启动用户的主目录」里写文件 ——
     * 那个目录多半在容器里、随容器销毁而消失，而错误要等到用户反馈
     * 「封面过两天就没了」才暴露。
     */
    private String path;

    /**
     * URL 前缀，必须和 {@code SecurityConfig.WHITELIST} 里的 {@code /uploads/**} 一致。
     *
     * <p>★ 改这个值要同时改白名单，否则会出现「上传成功但图片加载不出来，
     * 且浏览器控制台显示 401」—— 因为图片地址落进了「需要登录」的范围。
     */
    private String urlPrefix = "/uploads/";

    /**
     * 启动时把目录建好，并打一行日志。
     *
     * <p><b>★★ 这个 {@code createDirectories} 不是「顺手做的好事」，它是必须的</b> ——
     * 见 {@link #getResourceLocation()} 里关于结尾斜杠的说明。
     *
     * <p>★ 在 {@code @PostConstruct} 里做而不是第一次上传时做：目录建不出来
     * （磁盘满、没权限、路径写错）是一个<b>部署配置问题</b>，
     * 应该在启动时就炸掉、让人立刻看见；放到第一次上传时才抛，
     * 就变成了「服务跑了一周都好好的，直到有个作者点了上传」。
     */
    @PostConstruct
    void init() {
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException(
                    "配置 novel.upload.path 没有设置。开发环境的默认值在 application-dev.yml，"
                            + "生产环境请通过环境变量或部署配置指定一个【外部绝对路径】"
                            + "（★ 不要放在 resources/static 下，那会被打进 jar 包）。");
        }
        Path root = getRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("上传目录创建失败：" + root, e);
        }
        log.info("上传目录就绪 | dir={} | url={}** | 静态资源映射={}",
                root, normalizedPrefix(), getResourceLocation());
    }

    /**
     * 上传根目录的<b>绝对</b>路径。
     *
     * <p>★ 每次调用重新算，不缓存成字段：这个类的字段是要被配置绑定器
     * 通过 setter 写入的，而「算出来的值」和「配置里的值」混在同一个字段列表里，
     * 看代码的人分不清哪个能手改哪个不能。算一次的代价是一次字符串解析，
     * 相对一次文件上传可以忽略。
     */
    public Path getRoot() {
        return Paths.get(path).toAbsolutePath().normalize();
    }

    /**
     * 静态资源映射用的 location，形如 {@code file:///C:/Users/Asus/novel-uploads/}。
     *
     * <p><b>★★★ 结尾的那个斜杠是这段代码里最容易出事的一个字符。</b>
     *
     * <p>Spring 的 {@code ResourceHttpRequestHandler} 拿 location 拼请求路径时调的是
     * {@code Resource.createRelative(相对路径)}，而它的实现是
     * <b>「替换掉最后一个路径段」</b>，不是「拼接」。于是：
     * <pre>
     *   有斜杠： file:///C:/Users/Asus/novel-uploads/ + cover/a.jpg
     *            → file:///C:/Users/Asus/novel-uploads/cover/a.jpg   ✅
     *   没斜杠： file:///C:/Users/Asus/novel-uploads  + cover/a.jpg
     *            → file:///C:/Users/Asus/cover/a.jpg                 ❌ 少了 novel-uploads
     * </pre>
     * 症状是<b>每一张图都 404，但上传接口返回 200、文件也确实在磁盘上</b> ——
     * 排查时先去查「文件存哪了」，方向从一开始就是错的。
     *
     * <p>而 {@code Path.toUri()} <b>加不加这个斜杠取决于目录是否存在</b>
     * （它内部会 {@code Files.isDirectory} 一下）。也就是说，这段代码的正确性
     * 依赖 {@link #init()} 里的 {@code createDirectories} <b>先跑过</b>。
     * 这个依赖没有任何编译期保障，所以这里<b>不依赖它</b>，自己兜底补一个斜杠。
     * 上面那句日志把 location 打出来，也是为了这个 —— 万一哪天又坏了，
     * 日志里能直接看到缺没缺。
     */
    public String getResourceLocation() {
        String uri = getRoot().toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    /**
     * 资源处理器的路径模式，形如 {@code /uploads/**}。
     *
     * <p>交给 {@code WebMvcConfig} 注册映射用。
     */
    public String getUrlPattern() {
        return normalizedPrefix() + "**";
    }

    /**
     * 把「相对路径」拼成前端能直接用的 URL。
     *
     * <pre>
     *   cover/2026/09/20/ab12.jpg → /uploads/cover/2026/09/20/ab12.jpg
     * </pre>
     *
     * <p>★ 这里不调 {@code URLEncoder}：入参是<b>我们自己拼出来的</b>
     * （日期目录 + UUID + 白名单里的扩展名），不含任何用户输入的字符。
     * 真让它收到带空格或中文的路径，那说明上游有人在拼路径 ——
     * 那是要修上游，不是在这里编码盖住。
     */
    public String toUrl(String relativePath) {
        return normalizedPrefix() + relativePath;
    }

    /** 前缀可能配成 {@code /uploads}（少个尾部斜杠），这里统一补齐 */
    private String normalizedPrefix() {
        String prefix = StringUtils.hasText(urlPrefix) ? urlPrefix : "/uploads";
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
