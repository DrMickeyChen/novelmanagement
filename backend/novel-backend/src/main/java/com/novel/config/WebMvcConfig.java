package com.novel.config;

// ★ UploadProperties 在 M0 搬去了 com.novel.common.upload（和其它被共用的东西放一起），
//   而这个类留在单体 —— 于是它从「同包」变成了「跨包」，得显式 import。
//   这是 M0 全仓唯一一处新增的 import（另一处是 ImageStorage 里删掉的那行）。
import com.novel.common.upload.UploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 的定制点，目前只有一件事：<b>把上传目录暴露成一个静态资源路径</b>。
 *
 * <p><b>★★ 为什么 P4 才需要这个类 —— 这里有个存在了很久的「假配置」：</b>
 *
 * <p>{@code SecurityConfig.WHITELIST} 里的 {@code "/uploads/**"} 是 <b>P0 就加上的</b>，
 * 但直到 P4 之前，项目里<b>没有任何地方把 {@code /uploads/} 映射到磁盘目录</b>。
 * 也就是说那条白名单在过去几期里一直是<b>空放行</b>：
 * 放行了，但请求打过来是 404 —— 因为根本没有 Handler 接。
 *
 * <p>它是「假配置」而不是「错配置」：安全配置本身没错（访问图片确实不该要登录），
 * 错在<b>只有它的一半</b>。这类「配了一半」的东西最难发现 ——
 * 搜索 {@code uploads} 能搜到白名单那一条，看起来事情已经做完了。
 *
 * <p><b>白名单 + 资源映射是两件事，必须成对：</b>
 * <pre>
 *   白名单（SecurityConfig）  决定「能不能匿名访问」
 *   资源映射（本类）          决定「这个路径指向磁盘上的哪个目录」
 * </pre>
 * 少任何一个的症状完全不同：少白名单 → 图片地址返回 401；
 * 少映射 → 图片地址返回 404。两个都不会在启动时报任何错。
 *
 * <p><b>★ 为什么用 {@code WebMvcConfigurer} 而不是在 yml 里写
 * {@code spring.web.resources.static-locations}：</b>
 * 那个配置项是<b>覆盖整个默认静态资源处理</b>的，一写就会把
 * {@code classpath:/static/} 一起换掉，而 Knife4j 的 {@code /doc.html}
 * 正是靠那套默认映射提供的 —— 结果就是「改个图片路径，接口文档页 404」。
 * 用 {@code addResourceHandlers} 是<b>新增一条</b>映射，不动默认的那些。
 *
 * <p><b>★ 为什么可以匿名访问：</b>封面是<b>给读者看的</b>，读者端本来就不需要登录
 * （大纲 §5.3）。文件名是 UUID，目录是「日期 + 随机名」，
 * 猜不到别人的图 —— 但这不是安全边界，只是「不可枚举」。
 * 真正敏感的东西（未上架作品的正文）从来不走这个目录。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 封面图基本不变，而 URL 里的 UUID 保证「换了图就是换了 URL」，所以可以放心长缓存 */
    private static final int CACHE_SECONDS = 30 * 24 * 60 * 60;

    private final UploadProperties uploadProperties;

    /**
     * 把 {@code /uploads/**} 映射到配置的上传目录。
     *
     * <p><b>★ 结尾斜杠的坑在 {@link UploadProperties#getResourceLocation()} 里，
     * 这里是它的另一半</b>：location 少一个斜杠 → 每张图都 404，
     * 而上传接口返回 200、文件也确实在磁盘上。
     *
     * <p>★ {@code setCachePeriod} 敢设 30 天，是因为<b>文件名每次都不同</b>
     * （UUID）：改封面 = 换 URL，不存在「缓存里还是旧图」的问题。
     * 如果将来改成「按作品 id 命名、原地覆盖」，这个缓存期就必须去掉 ——
     * 否则作者换了封面，自己和所有读者看到的还是旧的那张，而这是没法靠刷新解决的。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(uploadProperties.getUrlPattern())
                .addResourceLocations(uploadProperties.getResourceLocation())
                .setCachePeriod(CACHE_SECONDS);
    }
}
