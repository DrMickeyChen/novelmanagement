package com.novel.modules.content.service;

import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 「按 userId 取昵称」—— <b>content 模块里唯一一处依赖 system 模块的地方。</b>
 *
 * <p><b>★ 为什么单独抽一个类，而不是在 Service 里直接注入 {@code SysUserMapper}：</b>
 *
 * <p>大纲 §178 定下的规矩是「<b>服务之间不共享数据库表</b>，跨服务取数据只能走 Feign 或 MQ」，
 * 而本项目的包结构就是按二期要拆的服务划的（{@code modules/content} ↔ 二期 {@code novel-content}，
 * {@code modules/system} ↔ 二期 {@code novel-auth}）。
 * <p>现在两个包在同一个进程、同一个库里，一句 {@code sysUserMapper.selectBatchIds(...)}
 * 就能拿到作者昵称 —— 但那正是二期拆分时最贵的一笔债：如果这个依赖散落在
 * NovelService、ChapterService、AuditLogService、各个 VO 转换里，
 * M2 拆服务时就得翻遍整个包去改。
 *
 * <p>所以这里把跨模块的耦合<b>收进一个类</b>：调用方只认
 * {@link #nicknameOf} / {@link #nicknamesOf}，不认 {@code SysUserMapper}。
 * 二期 M2 时只需要把这一个文件内部从「查库」改成「Feign 调 auth 服务」，
 * <b>调用方一行都不用动</b>。
 *
 * <p>大纲 §1277 把「详情页跨服务取到作者信息」列为 M2 的验收标准之一，
 * 说明这个需求是真实存在、且本来就要在 M2 变成跨服务调用的 —— 与其那时再补，
 * 不如现在就把接缝留好。
 *
 * <p><b>依赖方向提醒：</b>只能 content → system，绝不能反过来。
 * system 模块（认证/用户）不该知道小说的存在，否则二期的 auth 服务
 * 就反向依赖 content 了，拆分顺序（M1 先拆 auth、M2 再拆 content）直接作废。
 *
 * @author MickeyChen
 */
@Component
@RequiredArgsConstructor
public class ContentUserLookup {

    private final SysUserMapper sysUserMapper;

    /**
     * 取一个用户的昵称。查不到返回 {@code null}（用户可能已被注销）。
     *
     * <p>用于单条记录的场景（比如小说详情页的作者名）。
     */
    public String nicknameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getNickname();
    }

    /**
     * 批量取昵称，返回 {@code userId → nickname}。
     *
     * <p><b>★ 列表接口必须用这个，不要循环调 {@link #nicknameOf}：</b>
     * 一页 20 本小说就是 20 次查询，这是典型的 N+1。
     * 这里一次 {@code IN} 查询就够，而且 {@code sys_user.id} 是主键，
     * 20 个 id 走主键索引可以忽略不计。
     *
     * <p>返回 {@code Map} 而不是 {@code List}，是为了让调用方按 id 直接取 ——
     * 用 List 的话调用方得自己写个循环去配对，那种代码很容易在多角色、
     * 有重复 id 的情况下出错。
     *
     * @param userIds 用户 id 集合，允许为空（空集合直接返回空 Map，
     *                <b>不能拿空集合去查库</b> —— 那会生成 {@code WHERE id IN ()} 语法错误）
     */
    public Map<Long, String> nicknamesOf(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 去重：同一页里好几本书是同一个作者很常见，重复 id 没必要重复查
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, String> result = new LinkedHashMap<>();
        /*
         * ★ 用 selectByIds 而不是 selectBatchIds：后者在 MP 3.5.9 已经是
         * @Deprecated 的 default 方法（内部转调 selectByIds），编译只报一句
         * 「使用了已过时的 API」不报错，但它是【要被删掉】的那个 ——
         * 留着，下一次升 MP 就是编译不过。
         */
        for (SysUser user : sysUserMapper.selectByIds(ids)) {
            result.put(user.getId(), user.getNickname());
        }
        return result;
    }
}
