package com.novel.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.common.constant.CommonConstant;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.common.security.TokenStore;
import com.novel.common.security.UserContext;
import com.novel.modules.system.entity.SysRole;
import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.entity.SysUserRole;
import com.novel.modules.system.mapper.SysRoleMapper;
import com.novel.modules.system.mapper.SysUserMapper;
import com.novel.modules.system.mapper.SysUserRoleMapper;
import com.novel.modules.system.service.AdminUserService;
import com.novel.modules.system.vo.AdminUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端用户管理的实现。
 *
 * <p><b>★ 这个类只碰 {@code sys_user} / {@code sys_user_role} / {@code sys_role}
 * 三张表，全都在 {@code modules/system} 自己的地盘里</b> ——
 * 没有跨模块查询，第二期 M1 摘 auth 服务时可以整个搬走，不需要任何改造。
 * （对比：大盘 {@code DashboardService} 要跨表 join，那个才有拆分的麻烦。）
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;

    /* ==================== 分页 ==================== */

    @Override
    public PageResult<AdminUserVO> page(Integer status, String roleCode, String keyword,
                                        long page, long size) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(status != null, SysUser::getStatus, status);

        /*
         * ★★ 角色筛选先用 roleCode 查出 roleId，再用【参数化】的子查询过滤。
         *
         * 为什么不直接把 roleCode 拼进 SQL：`inSql` / `last` 这类方法
         * 是【原样拼接】的，传进去的字符串就是 SQL 的一部分 ——
         * `?roleCode=' OR 1=1 --` 就能把整个用户表拖出来。
         * 这里用 `apply` 的 {0} 占位符，它走的是 MyBatis 的 #{} 参数绑定，
         * 值永远只是值。（先查 id 这一步顺带把 roleCode 变成了 Long，
         * 但即使不转，{0} 也是安全的 —— 两道保险。）
         */
        if (StringUtils.hasText(roleCode)) {
            Long roleId = roleMapper.selectRoleIdByCode(roleCode.trim());
            if (roleId == null) {
                /*
                 * 角色码不认识 → 直接返回空页，不去查库。
                 *
                 * ★ 这里【不抛异常】：它是个筛选条件，语义就是
                 *   「哪个用户有这个角色」—— 角色都不存在，答案自然是「没有」。
                 *   抛 400 的话，前端传一个已经删掉的角色码（页面开着的时候
                 *   角色被别处删了）就会红字报错，而用户只是看到一个空列表而已。
                 *   但必须留 warn：正常流程里不该出现。
                 */
                log.warn("用户列表按不存在的角色筛选 | roleCode={}", roleCode);
                return PageResult.empty();
            }
            wrapper.apply("id IN (SELECT user_id FROM sys_user_role WHERE role_id = {0})", roleId);
        }

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            /*
             * ★★ 这个 and(...) 包一层是必须的，不是风格问题。
             *
             * 不包的话拼出来的是：
             *   WHERE status = 1 AND username LIKE '%x%' OR nickname LIKE '%x%'
             * SQL 里 AND 的优先级高于 OR，实际等于：
             *   WHERE (status = 1 AND username LIKE '%x%') OR (nickname LIKE '%x%')
             * 于是【筛选「已封禁」时会漏出所有昵称匹配的正常用户】——
             * 而结果看起来「也挺像那么回事」，很难发现。
             * 包成 and(w -> ...) 之后才是 (A AND (B OR C))。
             */
            wrapper.and(w -> w.like(SysUser::getUsername, kw)
                    .or()
                    .like(SysUser::getNickname, kw));
        }

        /*
         * 按 id 倒序 = 按注册时间倒序（id 是自增的），
         * 而且 id 是主键、全局唯一 —— 排序完全确定，翻页不会「自己跳」。
         * 用 create_time 排就不行：同一秒注册的两个用户顺序不保证稳定。
         */
        wrapper.orderByDesc(SysUser::getId);

        Page<SysUser> result = userMapper.selectPage(buildPage(page, size), wrapper);
        return new PageResult<>(toVOList(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    /**
     * 实体列表 → VO 列表，<b>顺带批量补齐角色</b>。
     *
     * <p><b>★★ 这里查了两次，不是三次、更不是 N 次，这是有意的：</b>
     * <pre>
     *   ① 本页全部 userId → 一次 IN 查出所有 sys_user_role 行
     *   ② 一次查出全部角色（个位数），在内存里做 id → code 的映射
     * </pre>
     * 逐条查角色就是经典的 N+1：一页 20 条变成 21 次查询。
     * 而它<b>结果完全正确</b>，功能测试里永远发现不了，
     * 只会在数据量上来之后表现为「用户列表越来越慢」。
     */
    private List<AdminUserVO> toVOList(List<SysUser> records) {
        if (records.isEmpty()) {
            /*
             * ★ 提前返回不只是省一次查询 —— 更重要的是
             *   `IN ()` 空集合在 SQL 里是【语法错误】。
             *   MP 对空集合的 in() 行为随版本变化（有的生成 IN ()，有的直接跳过），
             *   不依赖它。
             */
            return List.of();
        }

        List<Long> userIds = records.stream().map(SysUser::getId).toList();

        // ① 本页所有用户的角色关联，一次查完
        List<SysUserRole> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds));

        // ② 全部角色，一次查完（角色是个位数量级的字典表）
        Map<Long, String> codeById = new HashMap<>();
        for (SysRole role : roleMapper.selectList(null)) {
            codeById.put(role.getId(), role.getRoleCode());
        }

        /*
         * ★ 用 LinkedHashMap + 逐条 append，而不是 groupingBy：
         *   角色的展示顺序由 sys_user_role 的查询顺序决定，
         *   而那个顺序数据库不保证稳定 —— 同一个人刷新两次可能
         *   显示成「ADMIN,AUTHOR」和「AUTHOR,ADMIN」。
         *   这里保持行序虽然也不保证，但至少一次请求内是一致的；
         *   前端要稳定排序的话在展示层做，别在后端猜。
         */
        Map<Long, List<String>> rolesByUser = new LinkedHashMap<>();
        Map<Long, List<Long>> roleIdsByUser = new LinkedHashMap<>();
        for (SysUserRole rel : relations) {
            /*
             * ★ 角色被逻辑删除后，这一行 sys_user_role 还在（那张表没有 deleted 列）。
             *   过滤掉它，否则界面上会显示一个查不到的角色码，
             *   而 roleIds 里带着它还会让编辑弹窗勾中一个不存在的勾选框。
             *   ——「关联存在」不等于「角色存在」。
             */
            String code = codeById.get(rel.getRoleId());
            if (code == null) {
                continue;
            }
            rolesByUser.computeIfAbsent(rel.getUserId(), k -> new ArrayList<>()).add(code);
            roleIdsByUser.computeIfAbsent(rel.getUserId(), k -> new ArrayList<>()).add(rel.getRoleId());
        }

        return records.stream()
                .map(u -> AdminUserVO.of(u,
                        rolesByUser.getOrDefault(u.getId(), List.of()),
                        roleIdsByUser.getOrDefault(u.getId(), List.of())))
                .toList();
    }

    /* ==================== 封禁 / 解封 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long userId, Integer status) {
        SysUser user = requireUser(userId);

        boolean disabling = !CommonConstant.STATUS_ENABLED.equals(status);

        /*
         * ★★ 自锁防护第 1 处：不能封禁自己。
         *
         * 封禁自己 = 把自己踢下线，而且再也登不回来
         * （AuthServiceImpl 的 status 检查会返回 1003「账号已被禁用」）。
         * 系统里通常只有一个管理员账号，所以这等于把管理端永久锁死，
         * 恢复只能进 MySQL 手工把 status 改回 1。
         *
         * ★ 判断的是「操作者 == 被操作者」，不是「操作者是不是管理员」——
         *   管理员封禁【别人】是完全正常且必要的操作。
         */
        if (disabling && userId.equals(UserContext.getUserId())) {
            log.warn("拒绝封禁自己 | userId={}", userId);
            throw new BusinessException(ResultCode.USER_CANNOT_BAN_SELF);
        }

        user.setStatus(status);
        /*
         * ★ 用 updateById 而不是手写 UPDATE：它会自动跳过 null 字段
         *   （默认的 FieldStrategy 是 NOT_NULL），所以这里只会更新 status 列，
         *   不会把 password、nickname 等没动的字段冲成 null。
         */
        userMapper.updateById(user);

        /*
         * ★★ 这一行是封禁功能的【全部要害】，删掉它整个功能就是假的。
         *
         * 只改 status 的话：新登录会被拒（AuthServiceImpl 里查了 status），
         * 但【已经登录的人一点感觉都没有】—— JwtAuthenticationFilter
         * 只从 Redis 读身份，不查库，所以旧 token 继续有效，
         * 一直到 7 天后自然过期。界面上显示「已封禁」，
         * 而对方还在正常发评论、发作品。
         *
         * ★ 解封【不需要】这一句：对方本来就没会话（被封时已经清干净了）。
         *   这里没有写成「无论如何都清一次」，因为那是多做一次 SCAN ——
         *   而且会把「解封一个因为别的原因还留着会话的用户」这种边缘情况
         *   变成一次莫名其妙的掉线。
         */
        if (disabling) {
            int kicked = tokenStore.removeAll(userId);
            log.info("封禁用户 | userId={} username={} 已清理登录态={} 条",
                    userId, user.getUsername(), kicked);
        } else {
            log.info("解封用户 | userId={} username={}", userId, user.getUsername());
        }
    }

    /* ==================== 赋角色 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        SysUser user = requireUser(userId);

        /*
         * ★ 去重。前端传 [2, 2] 完全可能（用户连点了两下勾选框，
         *   或者前端拼参数时出了岔子），而不去重的话第二次 insert
         *   会撞 uk_user_role 唯一键 → DuplicateKeyException → 500。
         *   用户的意图是明确的（角色 2），没必要因此报错。
         */
        List<Long> distinctRoleIds = roleIds.stream().distinct().toList();

        /*
         * ★ 校验角色真的存在，而不是直接往 sys_user_role 里插。
         *   不校验的话，传一个不存在的 roleId 会成功插入一行
         *   —— 那是个【悬空关联】：登录时 selectRoleCodesByUserId
         *   会 join 不到角色，这个人就等于没有任何角色，
         *   表现是「登录成功但什么页面都进不去」，而且界面上看不出原因。
         *   （这正是不用外键的代价：一致性由 Service 层保证。）
         */
        List<SysRole> roles = roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>().in(SysRole::getId, distinctRoleIds));
        if (roles.size() != distinctRoleIds.size()) {
            log.warn("赋角色失败：角色不存在 | userId={} 传入={} 查到={}",
                    userId, distinctRoleIds, roles.size());
            throw new BusinessException(ResultCode.ROLE_NOT_FOUND);
        }

        /*
         * ★★ 自锁防护第 2 处：不能把自己身上的 ADMIN 拿掉。
         *
         * 拿掉之后左侧菜单里的「用户管理」「角色管理」立刻消失
         * （AdminLayout 按 permissions 过滤菜单，而权限是登录时写进 Redis 的 ——
         * 再加上下面那句 removeAll 会强制你重新登录、拿到新权限），
         * 于是【再也没有任何界面能把 ADMIN 加回来】，只能进 MySQL 手工 INSERT。
         *
         * ★ 判断条件刻意写得比「不能改自己的角色」更精确：
         *   只要最终结果里仍然有 ADMIN 就放行 —— 管理员给自己加一个
         *   别的角色（比如同时当作者）是完全正常的操作，不该被拦。
         *
         * ★ 必须在 deleteUserRoles 之前判断：先删后判的话，
         *   异常回滚虽然也能恢复（有 @Transactional），
         *   但把「不该发生的写操作」放进事务里，是白白依赖回滚。
         */
        if (userId.equals(UserContext.getUserId())) {
            boolean stillAdmin = roles.stream()
                    .anyMatch(r -> CommonConstant.ROLE_ADMIN.equals(r.getRoleCode()));
            if (!stillAdmin) {
                log.warn("拒绝移除自己的管理员角色 | userId={}", userId);
                throw new BusinessException(ResultCode.USER_CANNOT_REMOVE_OWN_ADMIN);
            }
        }

        /*
         * ★★ 覆盖式赋值：先全删、再全插，两件事在同一个事务里。
         *
         * sys_user_role 上没有 deleted 列，所以这里是【物理 DELETE】，
         * 这正是我们要的 —— 「关联不存在」就等于「行不存在」。
         * 见 SysUserRoleMapper#deleteUserRoles 上的说明。
         *
         * ★ 两条 SQL 必须同事务：中间失败的话，这个人会变成
         *   「一个角色都没有」—— 能登录但什么都干不了，
         *   而且从界面上完全看不出原因。
         */
        userRoleMapper.deleteUserRoles(userId);
        for (Long roleId : distinctRoleIds) {
            userRoleMapper.insertUserRole(userId, roleId);
        }

        /*
         * ★★ 改完角色必须踢下线，否则【这个功能等于没做】。
         *
         * 角色和权限是在【登录时】从库里查出来写进 Redis 的
         * （AuthServiceImpl 里那句注释「不放进 JWT —— 这样改了角色立刻生效」
         *   指的其实是「改了之后下次登录生效」）。
         * 不踢的话，被改的人界面上毫无变化 —— 管理员会以为没保存成功，
         * 然后再改一次，来回折腾。
         *
         * 踢掉之后对方被迫重新登录，登录时就拿到新角色了。
         */
        int kicked = tokenStore.removeAll(userId);
        log.info("修改用户角色 | userId={} username={} 角色={} 已清理登录态={} 条",
                userId, user.getUsername(),
                roles.stream().map(SysRole::getRoleCode).toList(), kicked);
    }

    /* ==================== 重置密码 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = requireUser(userId);

        /*
         * ★ 存 BCrypt 哈希，任何时候都不存明文 —— 和注册走的是同一个编码器。
         *   （编码器是唯一的那个 @Bean，所以 cost 值一致；
         *     自己 new 一个 BCryptPasswordEncoder 会有 cost 不匹配的风险，
         *     导致 matches() 静默返回 false，见 seed.sql 里的警告。）
         */
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);

        /*
         * ★★ 重置密码必须踢下线，而且这里比封禁那处更关键。
         *
         * 「重置密码」在真实场景里绝大多数是为了【把别人赶出去】
         * （账号疑似被盗、员工离职）。不踢的话，攻击者手里的 token
         * 在 7 天内继续有效 —— 管理员以为已经处理完了，
         * 而真正的风险窗口一点没变。
         *
         * ★ 不记录新密码到日志：日志会被采集、转发、长期保留，
         *   明文密码进去等于泄露。只记「谁重置了谁」。
         */
        int kicked = tokenStore.removeAll(userId);
        log.info("重置用户密码 | userId={} username={} 已清理登录态={} 条",
                userId, user.getUsername(), kicked);
    }

    /* ==================== 私有工具 ==================== */

    /**
     * 取用户，不存在就抛 {@code USER_NOT_FOUND(1001)}。
     *
     * <p>{@code selectById} 会自动带 {@code deleted = 0}（{@code @TableLogic}），
     * 所以「已注销的用户」在这里等同于「不存在」——
     * 这正是我们要的：不能对一个已删除的账号封禁或改角色。
     */
    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * 分页参数兜底。
     *
     * <p><b>★ {@code size} 的下界必须自己兜，不能只靠拦截器。</b>
     * {@code PaginationInnerInterceptor} 只管<b>上界</b>（{@code setMaxLimit(100)}），
     * 而 {@code size < 0} 在 MP 里是「<b>关闭分页</b>」的意思 ——
     * {@code ?size=-1} 会把整张表一次性查出来。
     * 这个坑在 {@code NovelServiceImpl} / {@code ChapterServiceImpl} 等
     * 每个 Service 里都各兜了一次，本项目就是这么一个约定。
     */
    private Page<SysUser> buildPage(long page, long size) {
        long safePage = page < 1 ? 1 : page;
        long safeSize = size < 1 ? CommonConstant.DEFAULT_PAGE_SIZE : size;
        return new Page<>(safePage, safeSize);
    }
}
