package com.novel.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.common.internal.AuthorApplyBriefDTO;
import com.novel.modules.system.entity.AuthorApply;
import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.mapper.AuthorApplyMapper;
import com.novel.modules.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>M1：被 novel-backend 通过 {@code /internal/**} 问到的那些「用户/申请」读查询。</b>
 *
 * <p>这四个方法<b>不是新写的</b> —— 它们的每一行都是从 novel-backend 原来的
 * {@code ContentUserLookup} 里搬过来的，连同注释。<b>搬家的理由只有一条：
 * 数据在谁那儿，查询就归谁写。</b>
 *
 * <p>改造前 {@code ContentUserLookup} 直接注入 {@code SysUserMapper} 查库 ——
 * 那时候两个包同进程、同库，查得到。M1 把 {@code modules/system} 搬进
 * novel-auth 之后，那些 Mapper 就不在 novel-backend 的 classpath 上了
 * （编译期就报「找不到符号」，这正是我们要的信号）。于是这些 SQL
 * 必须跟着表走，跑到 auth 这一侧来。
 *
 * <p><b>★ 为什么单独一个类，而不是把 SQL 写进 Controller：</b>
 * 下面每个方法都带着一条「为什么这么写」的判断 ——
 * 条数上限、单列投影的安全性、{@code selectByIds} 而不是
 * {@code selectBatchIds}。Controller 只该负责「把 HTTP 翻译成方法调用」，
 * 把这些判断塞进去，下一个人改接口时会顺手把它们删掉。
 *
 * <p><b>★★ 依赖方向提醒：</b>本类只读 {@code sys_user} / {@code author_apply}
 * —— 都是 auth 自己的表。它<b>不认识小说、章节、评论</b>，
 * 一个 {@code modules.content} 的 import 都不该出现：
 * 一旦出现，就是铁律 1 被打破了（system → content），
 * 二期接下来的 M2（摘 content）就拆不动了。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalUserQueryService {

    /**
     * 昵称模糊匹配的条数上限。
     *
     * <p>★ 100 是「够用」和「别把下游 SQL 撑爆」之间的取舍：搜到一个 100+ 人
     * 重名的昵称时，结果会被截断，搜索页会少几本书 —— 这是<b>可以接受的降级</b>。
     * 反过来不设上限，一次搜索可能带出几万个 id 去做 {@code IN}，
     * 那是一次真正的故障（SQL 文本超长、解析开销大）。
     */
    private static final int NICKNAME_MATCH_LIMIT = 100;

    private final SysUserMapper sysUserMapper;
    private final AuthorApplyMapper authorApplyMapper;

    /**
     * 取一个用户的昵称。查不到返回 {@code null}（用户可能已被注销）。
     *
     * <p>★ M1 之后这个 {@code null} 有了一层新含义：它还可能是
     * 「novel-backend 那边传了个不存在的 id」。但两种情况的处理是一样的
     * （显示为空），所以不需要区分。
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
     * 一页 20 本小说就是 20 次查询 —— 而 M1 之后每次查询还多了一跳 HTTP，
     * N+1 的代价从「20 次 SQL」变成了「20 次跨进程调用」，更不得不防。
     *
     * <p>返回 {@code Map} 而不是 {@code List}，是为了让调用方按 id 直接取。
     *
     * @param userIds 用户 id 集合，允许为空（空集合直接返回空 Map，
     *                <b>不能拿空集合去查库</b> —— 那会生成
     *                {@code WHERE id IN ()} 语法错误）
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

    /**
     * 反查：昵称模糊匹配 → 用户 id 列表。<b>给「按作者名搜索作品」用。</b>
     *
     * <p><b>★★ 这个方法是 {@link #nicknamesOf} 的逆向，而逆向的代价高得多。</b>
     * 正向是主键查（百万行也是一次 B+ 树下降），反向是
     * {@code nickname LIKE '%关键词%'} —— {@code sys_user.nickname}
     * <b>没有索引，而且前导通配符让任何索引都用不上</b>，这是一次全表扫描。
     * 所以：
     * <ul>
     *   <li>条数必须有 {@link #NICKNAME_MATCH_LIMIT} 上限，不能把全表扫出来的 id
     *       一股脑带回去做 {@code IN} —— 那会让下游的 SQL 变成巨长的 IN 列表；</li>
     *   <li>这是 P3 用 MySQL 做搜索的已知短板，二期 M4 上 ES 正是要解决它
     *       （连同 {@code novel.title} 那个 LIKE）。</li>
     * </ul>
     *
     * <p><b>★ M1 之后多了一条：上限还兼着「别让这个内部接口变成扫描器」的作用。</b>
     * 它现在是<b>一个可以被网络上的兄弟服务反复调用的端点</b>，
     * 而不再是一个进程内的方法。上限把它单次的开销钉死了。
     *
     * <p>★ <b>用 {@code LambdaQueryWrapper} 而不是手写 {@code @Select}</b>，
     * 理由和 {@code NovelMapper} 保持空接口一样：wrapper 让 {@code @TableLogic}
     * 自动补上 {@code deleted = 0}。手写就得自己记得写 ——
     * 忘了的话，<b>已注销用户的作品会混进搜索结果</b>，而且不报错。
     *
     * <p>★ 用 {@code .select(SysUser::getId)} 只取 id 一列，两个好处：
     * ① 不把 {@code password}（BCrypt 哈希）捞进内存 —— 泄露哈希等于
     * 把离线爆破的门槛降到「拖库」以下；② 少读几列，全表扫描时差别明显。
     *
     * <p>★ 单列投影在这里【安全】：主键永远不为 NULL，MyBatis 就不会把整行丢成
     * {@code null} 塞进 List。<b>但这是「碰巧安全」，不是「有保证」</b> ——
     * 选到可空列上会 500，实例见 {@code AdminRoleServiceImpl#permsOfMenus}。
     *
     * @param keyword 昵称关键词。调用方必须先判空 —— 空关键词会匹配所有人
     * @return 命中的用户 id，<b>可能为空列表</b>（调用方要短路，不能拿空集合去 {@code IN}）
     */
    public List<Long> userIdsByNickname(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }

        /*
         * ★ 这里的 .last("LIMIT 100") 和「绝不拼 SQL」那条规矩【不冲突】：
         * 拼进去的是一个编译期常量，不含任何来自请求的字符。
         *
         * 对照 NovelServiceImpl 里拒绝写的 .last("LIMIT " + limit) ——
         * 那里的 limit 来自请求参数，拼进去就是一个注入口。
         * 两者的区别是「值从哪来」，不是「用不用 .last」。
         */
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId)
                .like(SysUser::getNickname, keyword.trim())
                .last("LIMIT " + NICKNAME_MATCH_LIMIT));

        return users.stream().map(SysUser::getId).toList();
    }

    /**
     * 批量取作者申请的最少信息（id / 申请人 / 笔名），给 novel-backend 拼
     * 「墨白（张三）」这样的可读标题用。
     *
     * <p><b>★★ 为什么返回的是三列而不是一个拼好的标题：</b>
     * 标题要拼成「笔名（昵称）」，而昵称在<b>另一张表</b>里。
     * 如果在这里拼好，就等于把「标题长什么样」这个展示决策放进了 auth 服务
     * —— 而它是个展示问题，属于调用方。更要紧的是：novel-backend 那边
     * 对同一条申请可能还有别的取昵称的需求，拼死在字符串里就取不出来了。
     * <p>所以这里只给<b>事实</b>（哪个申请、谁提的、笔名叫什么），
     * 怎么呈现由消费方决定。
     *
     * @param applyIds 申请 id 集合，允许为空
     *                （<b>空集合直接返回空列表，不能拿它去查库</b> ——
     *                 那会生成 {@code WHERE id IN ()}，是语法错误）
     */
    public List<AuthorApplyBriefDTO> briefsOf(Collection<Long> applyIds) {
        if (applyIds == null || applyIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = applyIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<AuthorApply> applies = authorApplyMapper.selectByIds(ids);

        List<AuthorApplyBriefDTO> result = new ArrayList<>(applies.size());
        for (AuthorApply apply : applies) {
            AuthorApplyBriefDTO dto = new AuthorApplyBriefDTO();
            dto.setId(apply.getId());
            dto.setUserId(apply.getUserId());
            dto.setPenName(apply.getPenName());
            result.add(dto);
        }
        return result;
    }
}
