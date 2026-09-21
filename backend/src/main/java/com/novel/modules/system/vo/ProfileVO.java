package com.novel.modules.system.vo;

import com.novel.modules.system.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 个人资料出参 —— 读者端「个人中心」这一页要的全部字段。
 *
 * <p><b>★★ 为什么另开一个 VO，而不是把 {@code email} 加进 {@link UserVO}。</b>
 *
 * <p>{@code UserVO} 是「我」这个身份，它的每一个字段都会被三端的
 * {@code stores/user.js} 整个塞进 {@code localStorage[novel_user]}，
 * 而且是<b>登录、注册、{@code /api/auth/me} 三条路径共用</b>的形状。
 * 它是三个应用共享的一份事实标准 —— 加字段不会让谁编译不过，
 * 但会让「登录响应里为什么有 email」变成一个没人能回答的问题：
 * 三端没有一处读它。
 *
 * <p>这和 {@code ReadHistoryVO} 那条「不要为了将来的那个页面，让现在的阅读器
 * 多背三个用不上的字段」是同一条规矩。个人中心是 {@code email} 唯一的消费者，
 * 所以它自己带一个 VO。
 *
 * <p><b>★ 这个类的字段是「读」用的，不是「写」用的：</b>
 * 可改的只有 {@code nickname}（{@code PUT /api/reader/profile}）和
 * {@code avatar}（{@code POST /api/reader/profile/avatar}）；
 * {@code username} / {@code email} <b>没有修改入口</b> ——
 * 用户名是登录凭据（改了等于换账号，还要处理「旧 token 属于谁」），
 * 邮箱在本项目没有验证流程，能随便改就只是个摆设。
 * 它们出现在这里是为了让个人中心能把资料显示全，不是为了给前端暗示可以改。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "个人资料")
public class ProfileVO {

    @Schema(description = "用户 ID", example = "2")
    private Long id;

    /**
     * 登录名，<b>只读</b>。
     *
     * <p>个人中心必须把它显示出来：用户改完昵称之后，界面上就再也看不到
     * 自己的登录名了 —— 而登录名是他下次登录时唯一要输入的东西。
     */
    @Schema(description = "登录名（只读，登录时用）", example = "reader01")
    private String username;

    @Schema(description = "昵称（可改）", example = "爱看书的猫")
    private String nickname;

    /**
     * 头像 URL，没设过是 {@code null}。
     *
     * <p>★ <b>前端必须写 {@code avatar || 默认头像}，不能写 {@code avatar === null}。</b>
     * 理由同 {@link UserVO#getAvatar()}：这份数据也会被存进 localStorage，
     * 而 {@code undefined} 和 {@code null} 在 {@code === null} 下是两种结果。
     */
    @Schema(description = "头像 URL，未设置为 null", example = "/uploads/avatar/2026/09/21/ab12.jpg")
    private String avatar;

    @Schema(description = "邮箱（只读）", example = "reader01@example.com")
    private String email;

    /**
     * 从实体构造。
     *
     * <p>★ <b>刻意不提供 {@code from(LoginUser)} 重载。</b>
     * 本类的数据源只能是数据库 —— Redis 里那份登录态是<b>登录那一刻</b>的快照，
     * 用户改完昵称/头像之后它还是旧值（见 {@code LoginUser#avatar} 那段）。
     * 给一个从缓存构造的口子，早晚会有人在「改完之后回显」的路径上用它，
     * 于是页面显示的是刚改之前的值 —— 而且刷新一下又对了，
     * 是最难被当成 bug 报告的那种表现。
     */
    public static ProfileVO of(SysUser user) {
        ProfileVO vo = new ProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setEmail(user.getEmail());
        return vo;
    }
}
