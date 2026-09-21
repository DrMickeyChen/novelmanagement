package com.novel.modules.system.service;

import com.novel.modules.system.dto.ProfileUpdateDTO;
import com.novel.modules.system.vo.ProfileVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料 —— 读者端「个人中心」的后端。
 *
 * <p><b>★★ 为什么它落在 {@code modules/system} 而不是 {@code modules/interaction}：</b>
 * 这三个接口从头到尾只碰 {@code sys_user} 一张表 —— 昵称、头像、邮箱全在那一行上。
 * 二期拆服务时，「谁拥有 {@code sys_user}」的答案决定了它归谁：那是
 * <b>认证服务的表</b>（{@code AuthServiceImpl} 就在这里查它）。
 * 放进 interaction 会形成一个「交互服务直接读写认证服务的表」的口子，
 * 拆的时候就得多定义一条接缝，而这条接缝除了这一个页面没有任何别的消费者。
 *
 * <p>★ 路径 {@code /api/reader/**} 和类所在的模块<b>没有对应关系</b> ——
 * {@code /api/reader/} 表达的是「使用者是读者」，不是「代码属于哪个模块」。
 * 先例是 {@code ReaderAuthorApplyController}（同样在 system，同样挂
 * {@code /api/reader/}），那边把这条理由写得很细，可以对照着看。
 *
 * <p><b>★ 三个方法都<b>不</b>从 Redis 的登录态里取昵称/头像。</b>
 * 那份登录态是<b>登录那一刻</b>的快照（见 {@code LoginUser#avatar}），
 * 改完再读它是旧值。资料页的数据源只能是数据库 ——
 * 这也是 {@link ProfileVO} 不提供 {@code from(LoginUser)} 重载的原因。
 *
 * @author MickeyChen
 */
public interface ProfileService {

    /**
     * 查我的资料。
     *
     * <p>★ 用户 id 从登录态取（{@code UserContext}），<b>入参里没有 userId</b> ——
     * 否则就是一个「看别人邮箱」的接口。
     */
    ProfileVO get();

    /**
     * 改昵称。
     *
     * @return 改完之后<b>重新从库里读出来</b>的资料
     */
    ProfileVO updateNickname(ProfileUpdateDTO dto);

    /**
     * 上传头像并<b>直接生效</b>（落盘 + 写回 {@code sys_user.avatar} 一步完成）。
     *
     * <p>★ 不做成「先上传拿 URL、再 PUT 保存」两步：那样第二步就必须接收一个
     * 调用方传来的 URL 字符串，而服务端对那个字符串<b>再也做不了任何校验</b>
     * —— 魔数校验发生在第一步，第二步只能选择相信。合成一步之后，
     * 写进数据库的 URL 永远是 {@code ImageStorage} 自己生成的那个。
     *
     * @return 换完头像之后的资料（含新 URL）
     */
    ProfileVO uploadAvatar(MultipartFile file);
}
