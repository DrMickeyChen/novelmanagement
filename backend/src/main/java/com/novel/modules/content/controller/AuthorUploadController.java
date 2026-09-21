package com.novel.modules.content.controller;

import com.novel.common.result.Result;
import com.novel.common.upload.ImageStorage;
import com.novel.common.upload.ImageStorage.ImageKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 作者端 —— 封面上传。
 *
 * <p><b>★ 只有一个接口，而且它【不碰数据库】。</b>上传和「把 URL 填进作品信息」
 * 是两步：先拿到 URL，再在保存作品时带上 {@code coverUrl}。合成一步的话，
 * 「换封面」就得先有个作品 —— 而作者在<b>新建作品表单里</b>就要能传封面。
 *
 * <p><b>★★ 校验与落盘的全部实现（不可信的文件名 / 魔数是唯一放行依据 /
 * 外部绝对路径 / 只 transferTo 一次）都在 {@link ImageStorage} —— P6 抽出去是为了
 * 让读者头像复用同一份，而不是把 {@code docs/P4-创作中心.md} D-10 的安全逻辑
 * 复制成两份。</b>那个类的注释里有完整论证，这里不再重复。
 *
 * <p>★ 本类里<b>没有</b>「文件大小」校验，看着像漏了，其实是刻意的：
 * 封面的上限就是 {@code spring.servlet.multipart.max-file-size} 那一个数，
 * 超限时 Tomcat 在<b>请求解析阶段</b>就抛 {@code MaxUploadSizeExceededException}，
 * 根本走不到这里。同理 {@code ImageKind.COVER} 的 {@code maxBytes} 是 {@code null} ——
 * 写第二个上限值，两个数字迟早会不一致。
 * 那个异常的落点见 {@code GlobalExceptionHandler#handleMaxUploadSize}。
 *
 * @author MickeyChen
 */
@Slf4j
@Tag(name = "17. 作者端 · 上传", description = "封面等文件的上传。★ 需要 AUTHOR 或 ADMIN 角色")
@RestController
@RequiredArgsConstructor
public class AuthorUploadController {

    private final ImageStorage imageStorage;

    @Operation(
            summary = "上传封面图",
            description = """
                    返回可直接使用的 URL，形如 `/uploads/cover/2026/09/20/ab12cd34.jpg`。

                    **★ 上传和保存是两步**：拿到 URL 后，把它填进 `coverUrl` 再保存作品。
                    这样「新建作品」表单里也能先传封面。

                    **三种校验（缺一不可）：**
                    - 扩展名白名单：`jpg` / `jpeg` / `png` / `webp`
                    - Content-Type 白名单（廉价预筛，**不是安全控制**）
                    - **文件头魔数** —— 唯一的放行依据

                    ★ 只校验扩展名等于没校验：把 `shell.jsp` 改名成 `a.jpg`，
                    前两项全部通过。

                    **★ 落盘文件名由后端生成**（UUID + 规范后缀），
                    目录是 `cover/yyyy/MM/dd/`。客户端传来的文件名
                    **只用于比对，从不参与路径拼接** —— 所以 `../../x.jpg`
                    这类名字不可能写到别的地方，不是靠转义挡住的，
                    是压根没用上。

                    **错误：**
                    - `400` 空文件 / 类型不支持 / 后缀与实际内容不符
                    - `400` 超过 10MB（由 Spring 的 multipart 限制拦下，
                      ★ 走的是 `MaxUploadSizeExceededException` 那个 handler，
                      不是这里）

                    上传目录不可写等**服务端**问题返回 `500` —— 那是部署配置问题，
                    不是用户的错，不该给他一个「再试一次」的引导。""")
    @PostMapping("/api/author/upload/cover")
    public Result<String> uploadCover(@RequestParam("file") MultipartFile file) {
        return Result.success("上传成功", imageStorage.store(file, ImageKind.COVER));
    }
}
