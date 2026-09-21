package com.novel.modules.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.modules.system.entity.AuthorApply;
import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.mapper.AuthorApplyMapper;
import com.novel.modules.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>content 模块望向 system 模块的那【唯一一个窗口】。</b>
 *
 * <p>它主要做「按 userId 取昵称」，但不止于此 —— S11 起它也负责
 * 「按作者申请 id 取一句可读的标题」（见 {@link #authorApplyTitlesOf}）。
 * <b>★ 类名因此变得不够准确，这是有意接受的</b>：准确的命名应该是
 * {@code SystemModuleFacade} 之类，但那要改 5 个调用点换不来任何功能，
 * 而且会让人以为这里是个新概念。真正的规矩写在下面这段和
 * {@code AuthorApplyAuditRecorder} 的类注释里：<b>跨模块的耦合只能有一个出入口，
 * 出入口在消费方那一侧。</b>
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

    /**
     * ★ S11 加的第二个依赖，也是唯一一个不是「用户表」的。
     *
     * <p>它存在的原因见 {@link #authorApplyTitlesOf} —— 一句话：
     * content 要补出「作者申请」的可读标题，而那张表在 system 里。
     * <p><b>★ 为什么加在这里而不是新建一个 {@code AuthorApplyLookup}：</b>
     * 「跨模块的耦合只能有一个出入口」—— 再加一个类就是同一个方向上
     * 开了第二个口子，M1 拆 auth 时得找两个地方，而且以后每加一张表
     * 就会多一个 Lookup 类。宁可让这个类的名字变得不准确。
     */
    private final AuthorApplyMapper authorApplyMapper;

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
         * 写这个注释是因为后来人看到 .last 很容易以为是漏网的拼接，
         * 顺手「修」成参数化 —— 而 .last 恰恰是参数化不了的（它进的是 SQL 尾巴）。
         */
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId)
                .like(SysUser::getNickname, keyword.trim())
                .last("LIMIT " + NICKNAME_MATCH_LIMIT));

        return users.stream().map(SysUser::getId).toList();
    }

    /**
     * 批量取作者申请的「标题」，返回 {@code applyId → "墨白（张三）"}。
     * <b>给审核日志补「作者申请 #5」之外的可读信息用。</b>
     *
     * <p><b>★★ 为什么不直接用 {@link #nicknameOf}：</b>
     * {@code audit_log.target_id} 对 {@code AUTHOR_APPLY} 存的是<b>申请表的 id</b>，
     * 不是申请人的 {@code user_id}。拿它去调 {@code nicknameOf(applyId)}
     * <b>不会报错</b> —— 它会去 {@code sys_user} 里找 id 等于这个申请号的人，
     * 而那多半是<b>另一个真实存在的人</b>，于是日志页上显示的是
     * 「别人的申请」。两份申请都真实存在，从流水上根本看不出记错了。
     * <p>所以必须走 {@code apply_id → user_id → nickname} 两跳。
     *
     * <p><b>★ 标题为什么是「笔名（昵称）」而不是只有昵称：</b>
     * 昵称可以重复、可以随时改，而审核员在日志页要找的是「哪一份申请」。
     * 笔名是那份申请自己的内容，昵称说明了是谁 —— 两个都给才够定位。
     * （同一页里同一个人申请了两次、笔名不同，只有这样才分得开。）
     *
     * <p><b>★ 查不到的 id 不会出现在 Map 里</b>：申请被逻辑删除、
     * 或者申请还在但申请人已注销导致笔名取不到任何佐证 ——
     * 前者返回 {@code null}，日志页显示成「作者申请 #5（已删除）」，
     * 和作品 / 章节 / 评论的处理完全一致
     * （见 {@code AuditLogVO#targetTitle}：<b>为了补出已删除对象的标题
     * 而绕过逻辑删除是错误的修法</b>）。
     * <p>★ 申请人注销但申请还在时，<b>标题仍然给得出</b>（只有笔名）——
     * 申请本身没有消失，不该因为作者走人就显示成「已删除」。
     *
     * @param applyIds 申请 id 集合，允许为空
     *                （<b>空集合直接返回空 Map，不能拿它去查库</b> ——
     *                那会生成 {@code WHERE id IN ()}，是语法错误）
     */
    public Map<Long, String> authorApplyTitlesOf(Collection<Long> applyIds) {
        if (applyIds == null || applyIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = applyIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        List<AuthorApply> applies = authorApplyMapper.selectByIds(ids);
        if (applies.isEmpty()) {
            return Collections.emptyMap();
        }

        /*
         * ★ 第二次查询：申请人昵称。同样是一次 IN，不是循环 ——
         *   一页 20 条日志最多 20 个申请人，逐个查就是 20 次。
         * ★ 用 nicknamesOf（本类自己的方法）而不是再写一遍 selectByIds：
         *   昵称的取法只能有一处实现，将来它改成调 auth 服务时，
         *   这条路径自动跟着走。
         */
        Map<Long, String> nicknames = nicknamesOf(
                applies.stream().map(AuthorApply::getUserId).toList());

        Map<Long, String> result = new LinkedHashMap<>();
        for (AuthorApply apply : applies) {
            String penName = apply.getPenName();
            String nickname = nicknames.get(apply.getUserId());

            /*
             * ★ 申请人已注销时只有笔名 —— 降级，但不跳过：
             *   申请本身还在，不该因为作者走人就显示成「已删除」。
             *   （pen_name 列是 NOT NULL，所以这个分支一定给得出东西。）
             */
            String title = penName == null ? nickname : penName;
            if (title == null) {
                /*
                 * ★ 两样都没有，只能是不放进去 —— 调用方 get 出 null，
                 *   按「已删除」处理。这正是我们要的兜底，不是错误。
                 */
                continue;
            }
            /*
             * ★ 昵称和笔名相同时不写成「墨白（墨白）」——
             *   那看起来像数据重复，而不是「笔名恰好等于昵称」。
             */
            result.put(apply.getId(), nickname == null || nickname.equals(penName)
                    ? title
                    : title + "（" + nickname + "）");
        }
        return result;
    }
}
