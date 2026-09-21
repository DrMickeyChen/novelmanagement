package com.novel.modules.system.controller;

import com.novel.common.result.Result;
import com.novel.modules.system.dto.ProfileUpdateDTO;
import com.novel.modules.system.service.ProfileService;
import com.novel.modules.system.vo.ProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 读者端 —— 个人资料（昵称 / 头像）。
 *
 * <p><b>★ 路径是 {@code /api/reader/profile}，类在 {@code modules/system} ——
 * 这两件事没有矛盾，是本模块的固定写法。</b>
 * {@code /api/reader/} 说的是「使用者是读者」，不是「代码属于哪个模块」；
 * 而这三个接口只碰 {@code sys_user} 一张表，那是认证服务的表。
 * 同族的先例是 {@code ReaderAuthorApplyController} 和 {@code ReaderNoticeController}，
 * 前者把理由写得很细。
 *
 * <p><b>★ 落在 {@code anyRequest().authenticated()} 里，不用改 {@code SecurityConfig}</b>
 * ——这是选 {@code /api/reader/**} 而不是别的路径的<b>唯一实际好处</b>：
 * 任何登录用户都能调，不需要新增白名单条目。而这条路径上的接口本来就
 * 只允许「改自己」，匿名进去了也没有意义（{@code UserContext.requireUserId()} 直接抛 401）。
 *
 * <p><b>★★ 为什么这个类【不】复用 {@code /api/auth/me}：</b>
 * {@code me} 读的是 Redis 里的登录态（{@code UserVO.from(LoginUser)}），
 * 那是一份<b>登录那一刻的快照</b>。用它做「改完昵称的回显」，
 * 用户会看到自己刚改的名字变回去了 —— 刷新一下又对，最难被当成 bug 的那种。
 * 本类的三个接口一律<b>从数据库读</b>，见 {@link ProfileService} 的类注释。
 *
 * @author MickeyChen
 */
@Slf4j
@Tag(name = "32. 读者端 · 个人资料", description = "查资料 / 改昵称 / 传头像。★ 需要登录")
@RestController
@RequestMapping("/api/reader/profile")
@RequiredArgsConstructor
public class ReaderProfileController {

    private final ProfileService profileService;

    @Operation(
            summary = "我的资料",
            description = """
                    返回 `id` / `username` / `nickname` / `avatar` / `email`。

                    ★ **数据源是数据库，不是 Redis 里的登录态** ——
                    改完昵称/头像立刻再查一次，拿到的是新值。

                    ★ `avatar` 没传过时**整个 key 消失**
                    （全局 `default-property-inclusion: non_null`），
                    前端写 `avatar || 默认头像`，**不要写 `avatar === null`**。

                    ★ `username` / `email` 是**只读**的：它们出现在这里是为了让
                    个人中心能把资料显示全。本项目没有改用户名和改邮箱的入口
                    （用户名是登录凭据；邮箱没有验证流程，能随便改就只是个摆设）。""")
    @GetMapping
    public Result<ProfileVO> get() {
        return Result.success(profileService.get());
    }

    @Operation(
            summary = "修改昵称",
            description = """
                    `{"nickname": "爱看书的猫"}` —— **必填**，1~20 字（前后空格会被去掉）。

                    ★ **昵称就是评论区的显示名**：改完之后，书评 / 章评列表里
                    所有属于我的评论**下一次请求就会显示新昵称** ——
                    因为昵称是查询时按 `user_id` 现查的（`ContentUserLookup`），
                    不存在任何一处把旧昵称存下来。
                    这也是唯一一个「改一处、界面上到处跟着变」的字段。

                    ★ **允许重名**：昵称不是唯一标识，`sys_user` 上只有
                    `uk_username` 那条唯一约束。

                    ★ 返回改完之后的完整资料，前端直接拿它覆盖本地缓存即可。

                    **错误：**
                    - `400` 昵称为空 / 超过 20 字
                    - `200 + 1001` 自己这一行在改的瞬间被管理员删了

                    ★ **这里没有 `avatar` 字段** —— 头像走下面那个上传接口。
                    收一个 URL 字符串就等于允许把**任意 URL** 写进库里，
                    而且那个 URL 从不经过魔数校验。""")
    @PutMapping
    public Result<ProfileVO> update(@Valid @RequestBody ProfileUpdateDTO dto) {
        return Result.success("昵称已修改", profileService.updateNickname(dto));
    }

    @Operation(
            summary = "上传头像（上传即生效）",
            description = """
                    `multipart/form-data`，字段名 `file`。

                    **★ 一步完成**：校验 → 落盘 → 写回 `sys_user.avatar`，
                    返回新资料。**没有「先传拿 URL 再保存」那一步** ——
                    那样第二步就得接收调用方传来的 URL，而服务端对它
                    再也做不了任何校验（魔数校验发生在第一步）。
                    合成一步之后，写进库的 URL 永远是后端自己生成的那个。

                    ★ 返回的 `avatar` 形如 `/uploads/avatar/2026/09/21/ab12cd34.jpg`，
                    前端把它拼上网关地址（`http://localhost:3000`）就能显示。

                    **校验规则和封面上传完全一样**（同一份 `ImageStorage`）：
                    - 扩展名必须是 `jpg` / `jpeg` / `png` / `webp`
                    - **文件头魔数必须真的是图片** —— 唯一的放行依据
                    - ★ **头像额外有 2MB 上限**（封面没有）：
                      `max-file-size` 是全局的，改它会把封面一起限死，
                      所以头像的上限写在 `ImageKind.AVATAR` 里。
                      ★ 后端**不做缩放** —— 要更小的图请前端裁剪后再传。

                    **错误：**
                    - `400` 空文件 / 类型不支持 / 后缀与实际内容不符 / 超过 2MB

                    ★ **旧头像文件不会被删除**（已知项，P6 不处理）：
                    换 N 次头像就在磁盘上留 N 个文件，都不再被任何记录引用。""")
    @PostMapping("/avatar")
    public Result<ProfileVO> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Result.success("头像已更新", profileService.uploadAvatar(file));
    }
}
