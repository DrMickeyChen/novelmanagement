package com.novel.common.upload;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 图片落盘。封面上传（作者端）和头像上传（读者端）共用这一份实现。
 *
 * <p><b>★★★ 这个类的全部难点是：{@code MultipartFile} 上没有一样东西是可信的。</b>
 *
 * <p>一个上传请求里有三个可以声称「我是什么文件」的东西，它们的可信度<b>完全不同</b>：
 * <table border="1">
 *   <tr><th>来源</th><th>谁能控制</th><th>在本类里的角色</th></tr>
 *   <tr>
 *     <td>{@code getOriginalFilename()} 的扩展名</td>
 *     <td>❌ 客户端随便写</td>
 *     <td>只用来和「实际类型」<b>比对</b>；★ <b>不参与放行判断，更不参与路径拼接</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code getContentType()}（Content-Type 头）</td>
 *     <td>❌ 客户端的请求头</td>
 *     <td>廉价预筛，让明显不对的请求早点被拒。<b>不是安全控制</b></td>
 *   </tr>
 *   <tr>
 *     <td><b>文件头魔数</b></td>
 *     <td>✅ 只有真正构造文件内容才能伪造</td>
 *     <td>★ <b>唯一的放行依据</b></td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么魔数才是唯一可信的：</b>{@code shell.jsp} 改名叫 {@code a.jpg}，
 * 扩展名是 {@code jpg}、Content-Type 也是 {@code image/jpeg}（客户端自己填的），
 * 前两个检查<b>全都放行</b>，只有读文件头才能发现它压根不是图片。
 * 反过来说，<b>只校验扩展名等于没校验</b> —— 这是这一块最容易被面试问到、
 * 也最容易做错的地方。
 *
 * <p><b>★★ 关于「不可信的文件名」—— 本类的做法是让它彻底失去影响力：</b>
 * 落盘的文件名是 {@code UUID + 我们自己枚举里的扩展名}，日期目录也是自己算的，
 * 用户传来的那个字符串<b>只参与一个「相等/不等」的比较</b>。
 * 于是 {@code ../../../../etc/passwd.jpg} 这种名字不可能产生任何效果 ——
 * 不是因为我们对它做了转义，而是因为<b>它压根没被拼进路径</b>。
 * 「过滤危险字符」那种思路永远在补漏，让危险输入无处可去才是收敛的做法。
 *
 * <p>★ <b>本类放在 {@code common} 而不是某个 module 下，是硬约束不是偏好：</b>
 * 封面在 {@code modules/content}、头像在 {@code modules/system}，
 * 无论放进哪一边，另一个模块引用它就会形成 {@code system ↔ content} 的依赖 ——
 * 那是项目铁律明令禁止的（二期要先摘 auth，反了就有双向依赖拆不开）。
 * 磁盘 IO 既不属于「内容」也不属于「认证」，它是基础设施。
 *
 * <h3>★ M4：{@code @ConditionalOnProperty} 是给 novel-search 加的</h3>
 *
 * <p>本类的 {@code uploadProperties} 字段是 {@code final} + {@code @RequiredArgsConstructor}，
 * 也就是<b>构造器注入</b>。{@link UploadProperties} 一旦被 {@code @ConditionalOnProperty}
 * 跳过，这里就找不到可注入的 bean → <b>启动失败</b>。
 *
 * <p>★ 所以这个注解不是「顺手加的」，它是<b>那套条件的另一半</b>：
 * 跳过提供者就必须同时跳过所有消费者。search 里没有人会调 {@code store(...)}
 * —— 它既不收封面也不收头像 —— 所以跳掉它是准确的，不是将就。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "novel.upload", name = "path")
public class ImageStorage {

    /** 日期目录格式：{@code cover/2026/09/20/} —— 按天分目录，避免单目录堆几十万个文件 */
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 只读文件头这么多字节就够判断类型了（最长的是 png 的 8 字节 + webp 要看到第 12 字节） */
    private static final int HEAD_LEN = 12;

    private final UploadProperties uploadProperties;

    /**
     * 落盘的图片种类。<b>★ 目录名由类型系统定死，不由调用方传字符串决定。</b>
     *
     * <p>做成「一个通用上传接口 + 请求参数 {@code kind}」看起来更省，但那样
     * 落盘目录就是<b>调用方输入</b>的一部分，本类开头那句「用户输入不参与路径拼接」
     * 立刻变成假话。枚举让这件事在类型层面不可能发生。
     */
    public enum ImageKind {

        /**
         * 作品封面。
         *
         * <p>★ {@code maxBytes} 为 {@code null} 是<b>有意的</b>：封面的上限
         * 就是 {@code spring.servlet.multipart.max-file-size} 那一个数，本类不再写第二个。
         */
        COVER("cover", null),

        /**
         * 用户头像。
         *
         * <p>★ 这里的 2MB <b>不是</b>「和封面不一致的重复配置」，而是 Spring
         * <b>表达不了</b>的一条产品限制：{@code max-file-size} 是全局的，
         * 改它会把封面一起限死。头像是 200px 以内渲染的小图，
         * 2MB 已经够放一张手机原图，再大只是白占磁盘和被反复搬运的带宽。
         *
         * <p>★ 本类<b>不做缩放</b>：那需要引入图片解析器，等于把
         * 「图片解析器漏洞」这条路重新打开 —— 而上传接口最经典的一类漏洞就是它。
         * 要更小的图，由前端裁剪后再传。
         */
        AVATAR("avatar", 2L * 1024 * 1024);

        /** 落盘子目录名 */
        private final String dir;

        /** 本类额外强制的上限；{@code null} 表示不额外限制（交给 Spring 的 multipart 上限） */
        private final Long maxBytes;

        ImageKind(String dir, Long maxBytes) {
            this.dir = dir;
            this.maxBytes = maxBytes;
        }
    }

    /**
     * 支持上传的图片类型。
     *
     * <p><b>★ 用枚举而不是两个 {@code Set} 常量，是为了让「扩展名 ↔ 魔数 ↔ 存盘后缀」
     * 三者的对应关系<b>只有一处定义</b>。</b>写成三个散落的集合时，
     * 加一种格式要改三个地方，漏一个的症状是「某类图片上传成功但打不开」。
     *
     * <p>★ {@code jpg} 和 {@code jpeg} 是同一个类型（同一个魔数），
     * 但存盘统一用 {@code jpg}：留着用户的写法会让同一个类型出现两种后缀，
     * 以后做清理脚本 / 统计时要写两个条件。
     */
    private enum ImageType {

        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp");

        /** 存盘用的规范后缀（不是用户传来的那个） */
        private final String canonicalExtension;

        /** 对应的 MIME 类型，用来和客户端的 Content-Type 比对 */
        private final String mimeType;

        ImageType(String canonicalExtension, String mimeType) {
            this.canonicalExtension = canonicalExtension;
            this.mimeType = mimeType;
        }

        /**
         * 用户写的扩展名能不能算作这个类型。
         *
         * <p>★ 严格要求「声明的扩展名 == 实际类型」而不是「扩展名在允许列表里就行」：
         * 落盘的文件名会带上这个后缀，而 {@code WebMvcConfig} 那套静态资源映射
         * 是<b>按后缀决定 {@code Content-Type} 响应头</b>的。允许两者不一致，
         * 就会出现「响应头说是 png、实际字节是 jpg」的图 ——
         * 主流浏览器靠嗅探能显示，但这是一条「响应在说谎」的数据，
         * 以后任何按 Content-Type 处理的环节（CDN、图片处理、下载）都会踩到。
         */
        boolean acceptsExtension(String extension) {
            return switch (this) {
                case JPEG -> "jpg".equals(extension) || "jpeg".equals(extension);
                case PNG -> "png".equals(extension);
                case WEBP -> "webp".equals(extension);
            };
        }
    }

    /**
     * 校验并落盘，返回可直接使用的 URL，形如 {@code /uploads/avatar/2026/09/21/ab12cd34.jpg}。
     *
     * <p><b>校验顺序（每一步失败都不碰磁盘）：</b>
     * 空文件 → 大小 → 魔数（唯一的放行依据）→ 扩展名 → Content-Type。
     */
    public String store(MultipartFile file, ImageKind kind) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择要上传的图片");
        }
        if (kind.maxBytes != null && file.getSize() > kind.maxBytes) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "图片不能超过 " + kind.maxBytes / 1024 / 1024 + "MB");
        }

        String originalName = file.getOriginalFilename();
        String extension = extensionOf(originalName);

        /*
         * 只读文件头 12 个字节。★ 不需要把整个文件读进内存 ——
         * 10MB 的图 × 若干个并发上传，全量 getBytes() 会让堆占用直接跟着
         * 客户端输入走，是一条低成本的打爆内存的路子。
         */
        byte[] head = new byte[HEAD_LEN];
        int headSize;
        try (InputStream in = file.getInputStream()) {
            headSize = in.readNBytes(head, 0, HEAD_LEN);
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        }

        ImageType type = detectType(head, headSize);
        if (type == null) {
            reject(originalName, "文件头不是 jpg / png / webp 中的任何一种");
        }
        if (!type.acceptsExtension(extension)) {
            reject(originalName, "后缀 ." + extension + " 和文件实际内容（" + type.canonicalExtension + "）不符");
        }
        String declaredType = file.getContentType();
        if (declaredType != null && !declaredType.equalsIgnoreCase(type.mimeType)) {
            reject(originalName, "Content-Type(" + declaredType + ") 和文件实际内容不符");
        }

        /*
         * 到这里校验全部通过，才开始碰磁盘。
         *
         * ★ 顺序很重要：负向用例 12 要求「非法文件被拒绝时确认没有落盘」。
         *   先建目录再校验也能通过测试（目录不是文件），但那样失败时会在
         *   上传目录里留下一个空日期目录 —— 被扫描器刷一遍就是几百个空目录。
         */
        String relativeDir = kind.dir + "/" + LocalDate.now().format(DATE_DIR);
        Path dir = uploadProperties.getRoot().resolve(relativeDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + type.canonicalExtension;
        Path target = dir.resolve(filename);

        /*
         * ★ 防御性断言。上面两行拼出来的路径里没有任何用户输入，
         *   所以这里【不可能】失败 —— 正因为不可能，才更要留着：
         *   它把「拼出来的路径一定在根目录下」这个不变量写成了可执行的代码。
         *   将来有人改这段（比如允许传文件名、或加一层子目录），
         *   一旦拼出了根目录之外的路径，这里会立刻炸，而不是安安静静地
         *   往 C:\Windows 里写文件。失败时抛 IllegalStateException 而不是
         *   BusinessException —— 这是代码 bug，不是用户做错了什么。
         */
        if (!target.normalize().startsWith(uploadProperties.getRoot())) {
            throw new IllegalStateException("上传路径越界：" + target);
        }

        try {
            Files.createDirectories(dir);
            /*
             * ★★★ 这里**只** transferTo 一次，绝不能先把 head 写进去。
             *
             *   上面读文件头的那个 `getInputStream()` 是在
             *   **try-with-resources 里**，读完 12 字节流就关了；
             *   这里拿到的是一个**全新的、位置在 0 的流**。
             *
             *   所以「out.write(head); in.transferTo(out);」会写出
             *     【前 12 字节】+【整个文件】
             *   实测一张 70 字节的 PNG 落盘是 82 字节，PNG 签名
             *   `89 50 4E 47 0D 0A 1A 0A` 在文件里出现了两次。
             *
             *   ★ 这个 bug 的隐蔽之处：接口返回 200、Content-Type 是
             *     image/png、文件长度也不是 0，**每一条检查都是通过的** ——
             *     只有浏览器渲染时才会裂图。而封面正是那种「裂了也
             *     不影响其它功能」的东西，最容易一路带到演示现场。
             *     （我是拿 `buf.equals(PNG)` 比对字节发现的，不是看出来的。）
             *
             *   ★ 别为了「省掉重复读那 12 字节」把流提前打开跨过校验 ——
             *     校验失败时（reject 抛异常）那个流就漏了。
             *     多读 12 字节的代价是零，漏一个流描述符不是。
             *
             * ★ 用 CREATE_NEW 而不是默认的覆盖语义（也就不用 REPLACE_EXISTING）：
             *   UUID 撞车的概率约等于零，但万一撞上，静默覆盖掉另一个作者的封面
             *   是不可接受的。不覆盖时它会抛 FileAlreadyExistsException
             *   → 500 + 完整堆栈，比悄悄毁掉一张已发布的封面好得多。
             *   ☆ 显式写出 WRITE：`newOutputStream` 只在【一个 option 都不传】时
             *   才默认带上 WRITE，传了 option 就以传的为准 —— 只给 CREATE_NEW
             *   在某些 JDK 上会得到一个只读的流（写的时候才报错，很难联想到原因）。
             *
             * ★ 没有做「先写临时文件再原子改名」：写到一半进程被杀会留下一个
             *   残缺文件，但那个文件名是 UUID、数据库里没有任何记录指向它，
             *   影响是「磁盘上多一个垃圾文件」，不是「读者看到半张图」。
             *   为这个概率引入 .part 临时文件和二次改名不划算。
             */
            try (OutputStream out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 InputStream in = file.getInputStream()) {
                // ★ 只有这一行。`head` 的用途【仅限】上面那次魔数校验，不要再写它。
                in.transferTo(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传文件失败：" + target, e);
        }

        String url = uploadProperties.toUrl(relativeDir + "/" + filename);
        log.info("图片上传成功 | kind={} | user={} | 原始文件名={} | 大小={}KB | 存储={}",
                kind, UserContext.getUsername(), originalName, file.getSize() / 1024, url);
        return url;
    }

    /* ==================== 私有工具 ==================== */

    /**
     * 识别文件头，认不出来返回 {@code null}。
     *
     * <p><b>三者的签名：</b>
     * <pre>
     *   JPEG   FF D8 FF                        （第 3 字节是 FF 是 JFIF/EXIF 的共同前缀）
     *   PNG    89 50 4E 47 0D 0A 1A 0A         （"‰PNG\r\n\x1a\n"，8 字节）
     *   WEBP   52 49 46 46 ?? ?? ?? ?? 57 45 42 50   （"RIFF" + 4 字节长度 + "WEBP"）
     * </pre>
     *
     * <p>★ WEBP 要分两段看：{@code RIFF} 开头<b>不代表</b>就是 WEBP，
     * WAV 音频也是 {@code RIFF} 开头。中间那 4 个字节是文件长度（不定），
     * 所以必须跳到第 8 字节再看 {@code WEBP} 这四个字母。
     * 只判 {@code RIFF} 会把音频放进来。
     *
     * <p>★ 用 {@code headSize}（实际读到的字节数）而不是 {@code head.length} 判断长度：
     * 上传一个空文件时 stream 读到 0 字节，数组里全是 0，
     * 拿 {@code length} 判断会一路走进比对逻辑 —— 虽然结果同样是「不匹配」，
     * 但那是<b>碰巧</b>对的。写成读到的实际字节数，语义才是明确的。
     */
    private static ImageType detectType(byte[] head, int headSize) {
        if (headSize >= 3
                && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF) {
            return ImageType.JPEG;
        }
        if (headSize >= 8
                && (head[0] & 0xFF) == 0x89
                && (head[1] & 0xFF) == 0x50
                && (head[2] & 0xFF) == 0x4E
                && (head[3] & 0xFF) == 0x47
                && (head[4] & 0xFF) == 0x0D
                && (head[5] & 0xFF) == 0x0A
                && (head[6] & 0xFF) == 0x1A
                && (head[7] & 0xFF) == 0x0A) {
            return ImageType.PNG;
        }
        if (headSize >= 12
                && isAscii(head, 0, "RIFF")
                && isAscii(head, 8, "WEBP")) {
            return ImageType.WEBP;
        }
        return null;
    }

    /** 从 {@code offset} 开始是不是这四个 ASCII 字符 */
    private static boolean isAscii(byte[] bytes, int offset, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取小写的扩展名（不带点），取不到返回空串。
     *
     * <p>★ 先砍掉路径部分再取后缀。这一刀<b>不是为了安全</b>
     * （这个字符串从不参与路径拼接，见类注释），只是为了把后缀取对：
     * 有些客户端会传 {@code C:\\Users\\me\\a.jpg} 这种带完整路径的名字，
     * 而 {@code \\} 之后、{@code .} 之前的处理一旦搞混就会取错。
     *
     * <p>★ {@code dot > 0} 和 {@code dot < length - 1} 两个边界都要判：
     * <pre>
     *   ".jpg"   → dot == 0  → 没有扩展名（{@code .jpg} 在 Unix 里是隐藏文件，不是「后缀 jpg」）
     *   "a."     → dot 在最后 → 没有扩展名
     *   "a"      → 没有点   → 没有扩展名
     * </pre>
     * 这两种输入都会被当成「空扩展名」→ 在 {@code acceptsExtension} 那里失败，
     * 不需要单独的错误分支。
     */
    private static String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        String name = originalName.substring(Math.max(
                originalName.lastIndexOf('/'), originalName.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 拒绝一次上传，并留痕。
     *
     * <p>★ 用 {@code WARN} 且带上文件名：这一条既是「用户传错了」，
     * 也是<b>唯一能看出有人在试探上传接口的地方</b>。
     * 扫描器拿一堆 {@code .jsp} / {@code .php} 挨个试是每天都在发生的事，
     * 日志里能看到「同一个 IP 十分钟内被拒了 200 次」——
     * 这个信号只有在拒绝时打日志才存在。
     *
     * <p>★ 打的是<b>原始文件名</b>但不打它的内容：名字里那点信息
     * （{@code x.jsp}）正是判断意图需要的，而它会被 SLF4J 当参数拼进日志，
     * 不会被执行。★ 注意<b>绝不能</b>把文件名拼进「格式串」本身
     * （比如 {@code log.warn("拒绝：" + name)}），那只是习惯问题；
     * 真正的风险在于<b>拿到文件内容后不去解析它</b> —— 本类从头到尾
     * 只读 12 个字节做比对，从不解析图片，所以「图片解析器漏洞」这条路是关的。
     */
    private static void reject(String originalName, String reason) {
        log.warn("[非法上传] user={} | 文件名={} | 原因={}",
                UserContext.getUsername(), originalName, reason);
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "只支持 jpg / jpeg / png / webp 格式的图片（" + reason + "）");
    }
}
