package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 改个人资料的入参 —— <b>目前只有昵称一个字段</b>。
 *
 * <p><b>★ 为什么只有一个字段也要单独建一个 DTO，而不是直接用 {@code Map<String, String>}
 * 或者复用 {@code RegisterDTO}：</b>
 * 复用 {@code RegisterDTO} 会把 {@code password} 一起带进来 —— 那个字段在这条路径上
 * <b>没有任何作用</b>，但会让人以为「这里能改密码」。而改密码要先把所有设备踢下线
 * （旧 token 是用旧密码签的），是另一套交互，P6 不做。
 * 一个「能改密码但改了没用」的入参，比没有这个入参危险得多。
 *
 * <p><b>★ 这里没有 {@code avatar} 字段，是刻意的</b>：头像走独立的上传接口
 * （{@code POST /api/reader/profile/avatar}），由服务端用
 * {@code ImageStorage} 校验并<b>自己生成</b> URL。
 * 如果这里收一个 {@code avatar} 字符串，就等于允许调用方把
 * <b>任意 URL</b> 写进 {@code sys_user.avatar} —— 可以指向站外的追踪像素，
 * 也可以指向别人的头像；而且那个 URL 从来不经过魔数校验。
 * 「只接受自己生成的值」是一条比「校验传入的值」更省事的规则。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "修改个人资料")
public class ProfileUpdateDTO {

    /**
     * 新昵称。
     *
     * <p><b>★ 上限 20 和注册时的规则保持一致</b>（{@code RegisterDTO.nickname}）——
     * 数据库列是 {@code VARCHAR(50)}，但应用层的口径不能有两个：
     * 注册时 20、改名时 50 的话，用户注册后第一件事就是改一个更长的名字，
     * 而那个长度是注册表单永远给不了的。
     *
     * <p><b>★ 这里是 {@code @NotBlank}，比注册时严格</b>，不是笔误：
     * 注册允许不填（服务端用用户名兜底），而<b>改名时留空没有合理的兜底</b> ——
     * 兜成用户名等于「清空昵称」这个操作会静默变成「重命名成用户名」，
     * 用户看到的结果和他做的事对不上。想清空就直接报错让他重填。
     */
    @Schema(description = "新昵称", example = "爱看书的猫", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "昵称不能为空")
    @Size(max = 20, message = "昵称最长 20 个字符")
    private String nickname;
}
