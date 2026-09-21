package com.novel.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.common.upload.ImageStorage;
import com.novel.modules.system.dto.ProfileUpdateDTO;
import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.mapper.SysUserMapper;
import com.novel.modules.system.service.ProfileService;
import com.novel.modules.system.vo.ProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料实现。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final SysUserMapper sysUserMapper;

    /**
     * ★ 注入的是 {@code common.upload.ImageStorage}，不是某一份复制出来的上传逻辑。
     * 上传的校验规则（魔数、扩展名白名单、大小、UUID 文件名、日期目录、外部绝对路径）
     * 只有那一份实现，封面和头像共用 —— 复制一份的后果是「以后只改一份」，
     * 而这类漏洞不会报错。细节见 {@code docs/P4} 的 D-10。
     */
    private final ImageStorage imageStorage;

    @Override
    public ProfileVO get() {
        return ProfileVO.of(requireSelf());
    }

    /**
     * 改昵称。
     *
     * <p><b>★ 为什么是「按 id 更新单个字段」而不是「查出实体、set 完整个 updateById」：</b>
     * 两种写法都对，这里选前者是因为它<b>不可能顺手改到别的列</b>。
     * 整实体更新的写法里，任何一次「反正查出来了，顺手 set 一下」都会变成一个
     * 没人评审过的字段变更（尤其是 {@code status} / {@code password}）——
     * 而这条路径的入参里根本没有那些字段，说明它不该有改它们的能力。
     * 用 {@code LambdaUpdateWrapper} 把 SET 子句写死成一个 {@code nickname}，
     * 「这个接口能改什么」在代码里是一眼可见的。
     *
     * <p>★ <b>昵称不查重</b>：{@code sys_user} 上只有 {@code uk_username}，
     * 昵称本来就不是唯一标识。而且评论区显示的是昵称，重名是<b>产品上可接受的</b>
     * （现实里的昵称本来就允许重名），强行加唯一约束会让「改个别人用过的名字」
     * 变成一条看不懂的报错。
     *
     * <p>★ 不填 {@code update_time} —— 但这一次<b>不是</b>「不想动它」，
     * 而是这一列在 {@code sys_user} 上有 {@code ON UPDATE CURRENT_TIMESTAMP}，
     * 数据库自己会写。{@code sys_user.update_time} 的语义是「这行资料最后一次变更」，
     * 改昵称正是它的用途（对照 {@code novel.collect_count} 那种「改了别的表才顺带写一下」
     * 的冗余统计列，那种才要刻意不填）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProfileVO updateNickname(ProfileUpdateDTO dto) {
        Long userId = UserContext.requireUserId();
        // ★ 先取一次：既确认这个人还在（可能刚被管理员删了），也让日志里能带上改前的值
        SysUser before = requireSelf();

        String nickname = dto.getNickname().trim();
        int updated = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getNickname, nickname));
        if (updated == 0) {
            /*
             * ★ 走到这里说明 UPDATE 一行都没匹配上 —— 唯一的可能是这条记录
             *   在我们读完它之后被（逻辑）删了。抛 1001 而不是静默成功：
             *   静默成功的话用户会看到「已保存」，刷新之后昵称又变回去了。
             */
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        log.info("昵称已修改 | userId={} | {} -> {}", userId, before.getNickname(), nickname);
        // ★ 返回重新查出来的值，不是把入参拼回去 —— 让「接口返回的」和「库里真正存的」同源
        return ProfileVO.of(requireSelf());
    }

    /**
     * 上传头像并直接生效。
     *
     * <p><b>★★ 顺序是「先落盘、后写库」，不能反过来。</b>
     * 先写库再落盘的话，落盘失败（磁盘满、路径不可写）会留下一条
     * 「数据库里的头像 URL 指向一个不存在的文件」的记录 —— 页面上就是一张裂图，
     * 而且用户接下来<b>没有任何办法把它改回去</b>（他以为头像已经是这个了）。
     * 反过来先落盘，最坏的结果是磁盘上多一个没人引用的文件，界面完全正常。
     *
     * <p><b>★ 旧头像文件不删。</b> 换十次头像就在磁盘上留十个文件。
     * 删它需要知道「这个 URL 还有没有别人在用」（引用计数，或者扫描全表比对），
     * 成本远大于收益 —— 头像单文件 2MB 封顶，而这是个演示项目。
     * 记在 {@code docs/P6} 的已知项里，不做。
     *
     * <p>★ {@code @Transactional} 在这里保护的是<b>数据库那一侧</b>
     * （写库失败时不留半条记录），它管不到已经落盘的文件 ——
     * 这正是上面那条「顺序」要解决的问题，不要以为有了事务就万事大吉。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProfileVO uploadAvatar(MultipartFile file) {
        Long userId = UserContext.requireUserId();
        requireSelf();

        /*
         * ★ 校验 + 落盘 + 生成 URL 全在这一行里，包括文件头魔数校验。
         *   本方法【不】接受任何调用方给的路径/文件名/URL，也就不需要再校验它们。
         */
        String url = imageStorage.store(file, ImageStorage.ImageKind.AVATAR);

        int updated = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getAvatar, url));
        if (updated == 0) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        log.info("头像已更新 | userId={} | url={}", userId, url);
        return ProfileVO.of(requireSelf());
    }

    /**
     * 取当前登录用户的那一行，取不到就抛 1001。
     *
     * <p>★ 为什么不直接把 {@code UserContext.requireUserId()} 的返回值拿去用：
     * 登录态在 Redis 里有 TTL，而 {@code sys_user} 那一行<b>可能在这期间
     * 被管理员逻辑删掉</b>。没有这个检查的话，{@code ProfileVO.of(null)}
     * 会抛 NPE → 500，而正确答案是一个明确的 1001。
     *
     * <p>★ {@code selectById} 是 MyBatis-Plus 自己生成的方法，{@code @TableLogic}
     * 对它生效，所以已逻辑删的用户查不出来 —— 这里不需要手写 {@code deleted = 0}
     * （只有手写 {@code @Select} 才需要，见 {@code SysUserMapper.selectByUsername} 的注释）。
     */
    private SysUser requireSelf() {
        SysUser user = sysUserMapper.selectById(UserContext.requireUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }
}
