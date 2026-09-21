package com.novel.modules.system.service.impl;

import com.novel.common.constant.CommonConstant;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.JwtUtil;
import com.novel.common.security.LoginUser;
import com.novel.common.security.TokenStore;
import com.novel.common.security.UserContext;
import com.novel.modules.system.dto.LoginDTO;
import com.novel.modules.system.dto.RegisterDTO;
import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.mapper.SysRoleMapper;
import com.novel.modules.system.mapper.SysUserMapper;
import com.novel.modules.system.mapper.SysUserRoleMapper;
import com.novel.modules.system.service.AuthService;
import com.novel.modules.system.vo.LoginVO;
import com.novel.modules.system.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 认证服务的实现。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    /*
     * ★ 角色相关的两个 Mapper 是 P5 从 SysUserMapper 里拆出来的
     *   （那边原来的注释就写了「等做角色管理时再拆出去」）。
     *   拆完这里多注入两个依赖，SQL 一行没变。
     */
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;

    /* ==================== 登录 ==================== */

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUser user = userMapper.selectByUsername(dto.getUsername());

        /*
         * ★★ 防用户名枚举 —— 「查无此人」和「密码错误」返回【完全一样】的
         *    错误码和提示语，绝不能一个返 1001（用户不存在）
         *    另一个返 1002（密码错误）。
         *
         * 为什么：如果两者可区分，攻击者就能拿一个字典批量试用户名，
         * 根据返回码把「存在的用户名」筛出来（这叫用户枚举），
         * 拿到名单后再针对性地爆破密码 —— 也许还能顺便社工一下。
         * 而登录接口又是暴露在公网上、必须允许匿名访问的，
         * 是最容易被自动化扫描的地方。
         *
         * 代价是提示语没那么精确（用户打错用户名时会看到「用户名或密码错误」
         * 而不是「用户名不存在」）。这个代价是值得的：
         * 正常用户看提示会重新检查两项，不影响使用。
         */
        if (user == null) {
            log.warn("登录失败：用户不存在 | username={}", dto.getUsername());
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR);
        }

        /*
         * ★ 必须用 passwordEncoder.matches()，不能用 equals 比字符串。
         *
         * BCrypt 每次加密都会生成随机盐，同一个密码两次加密的结果完全不同，
         * 所以库里存的哈希和「把用户输入再加密一次」的结果永远不会相等。
         * matches(明文, 哈希) 会先从哈希里把盐取出来，再用同样的盐算一遍。
         */
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("登录失败：密码错误 | username={}", dto.getUsername());
            // ★ 和上面「用户不存在」同一个错误码，理由见上
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR);
        }

        /*
         * ★ 状态检查放在密码校验【之后】。
         *
         * 顺序反过来的话，「账号已被禁用」这个提示本身就是一条情报 ——
         * 攻击者不需要知道密码，输入任意密码就能判断出「这个用户名存在，
         * 只是被禁用了」，等于绕过了上面的防枚举设计。
         */
        if (!CommonConstant.STATUS_ENABLED.equals(user.getStatus())) {
            log.warn("登录失败：账号已禁用 | username={}", dto.getUsername());
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        // 角色和权限从库里现查，不放进 JWT —— 这样改了角色立刻生效
        List<String> roles = userRoleMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = userRoleMapper.selectPermsByUserId(user.getId());

        /*
         * ★ jti：每个 token 一个唯一编号。
         * 它出现在 Redis key 里（novel:auth:token:{userId}:{jti}），
         * 是「手机和电脑能同时在线」的关键 —— 详见 LoginUser.jti 的说明。
         */
        String jti = UUID.randomUUID().toString().replace("-", "");
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), jti);

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setNickname(user.getNickname());
        loginUser.setAvatar(user.getAvatar());
        loginUser.setJti(jti);
        loginUser.setRoles(roles);
        loginUser.setPermissions(permissions);

        // TTL 和 JWT 的有效期保持一致，两边同时失效
        tokenStore.save(loginUser, jwtUtil.getExpireMillis());

        log.info("登录成功 | userId={} username={} roles={}", user.getId(), user.getUsername(), roles);

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setTokenType("Bearer");
        vo.setExpiresIn(jwtUtil.getExpireMillis() / 1000);
        vo.setUser(UserVO.from(loginUser));
        return vo;
    }

    /* ==================== 注册 ==================== */

    /**
     * <b>{@code @Transactional} 在这里是必须的，不是可选的。</b>
     *
     * <p>注册要写两张表：{@code sys_user} 一条、{@code sys_user_role} 一条。
     * 如果中间失败（比如角色被删了导致 {@code selectRoleIdByCode} 返回 null 后抛异常），
     * 没有事务的话第一条 insert 已经提交了 —— 库里就留下一个
     * <b>没有任何角色的用户</b>。这种账号登录后 {@code roles} 是空数组，
     * 前端拿不到角色，表现是「登录成功但什么页面都进不去」，
     * 而且用户自己没法修复（用户名已经被占了，连重新注册都不行）。
     *
     * <p>顺带一提：Spring 的 {@code @Transactional} <b>默认只对
     * RuntimeException 回滚</b>。{@code BusinessException} 继承的是
     * RuntimeException，所以上面那种情况能正常回滚；
     * 如果哪天改成受检异常，这条就失效了 —— 这是很隐蔽的坑。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterDTO dto) {
        /*
         * 先查一次是为了给出友好的提示（1004 用户名已被注册），
         * 而不是让用户看到数据库的唯一键冲突报错。
         *
         * ★ 但这个检查【不能】当作保证：两个请求同时注册同一个用户名时，
         *   两边都可能查不到、都往下走。真正兜底的是下面的
         *   try-catch —— 数据库上 uk_username 那个唯一索引才是最终防线。
         *   （这种「先查再写」的竞态叫 TOCTOU，写业务代码时要意识到它。
         *   MyBatis-Plus 的 selectByUsername 带了 deleted = 0，
         *   所以它连「已被逻辑删除的同名用户」都查不出来，
         *   但那个用户名在数据库里其实还占着 —— 也会走到 catch 里。）
         */
        if (userMapper.selectByUsername(dto.getUsername()) != null) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        // ★ 存的是 BCrypt 哈希，任何时候都不存明文
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        // 昵称不填就用用户名兜底，省得前端到处写 nickname || username
        user.setNickname(dto.getNickname() == null || dto.getNickname().isBlank()
                ? dto.getUsername() : dto.getNickname());
        user.setStatus(CommonConstant.STATUS_ENABLED);

        try {
            // 主键由 MyBatis-Plus 回填到 user.id（@TableId(type = IdType.AUTO)）
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 撞上 uk_username 唯一索引：并发注册，或者用户名被已删除的用户占着
            log.warn("注册失败：用户名冲突 | username={}", dto.getUsername());
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }

        /*
         * ★★ 授予角色这一步是安全的关键：这里写死的 READER，
         *    不从入参拿。{@link RegisterDTO} 里压根没有 role 字段，
         *    就是为了让「前端传个 role:ADMIN 就变成管理员」这件事
         *    在结构上不可能发生。
         *
         * 想成为作者要走单独的「申请 → 管理员审核」流程（P2 做），
         * 不能靠注册接口自己声明。
         */
        Long readerRoleId = roleMapper.selectRoleIdByCode(CommonConstant.ROLE_READER);
        if (readerRoleId == null) {
            /*
             * 说明 sys_role 表里没有 READER 这一行 —— 通常是换了新库
             * 但忘了执行 docs/sql/seed.sql。这里必须抛异常让事务回滚，
             * 否则会留下一个没有任何角色的用户（后果见方法上的注释）。
             */
            log.error("注册失败：数据库里找不到 READER 角色，请确认已执行 docs/sql/seed.sql");
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "系统初始化数据缺失，请联系管理员");
        }
        userRoleMapper.insertUserRole(user.getId(), readerRoleId);

        log.info("注册成功 | userId={} username={} 已授予={}", user.getId(), user.getUsername(),
                CommonConstant.ROLE_READER);

        // 新用户还没有权限点（sys_role_menu 里 READER 是空的），所以传空列表
        return UserVO.of(user, List.of(CommonConstant.ROLE_READER), Collections.emptyList());
    }

    /* ==================== 登出 ==================== */

    @Override
    public void logout() {
        LoginUser loginUser = UserContext.get();
        if (loginUser == null) {
            /*
             * 正常走不到这里：/api/auth/logout 不在白名单里，
             * 未登录的请求在过滤器那关就被 401 挡掉了。
             * 留着这个判断是为了万一将来有人把它加进白名单 ——
             * 那时这里会抛错，而不是拿 null 去拼一个
             * "novel:auth:token:null:null" 的 key（那种 key 删不掉任何东西，
             * 表现为「点了退出但还是登录着」）。
             */
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        /*
         * ★ 只删【当前这一个 jti】的记录，这就是「手机登出不影响电脑」的实现。
         *   要踢掉某人的全部设备用 tokenStore.removeAll(userId)（管理端 P2 用）。
         *
         * 注意这里不抛「记录不存在」的错：重复点退出、或者 token 刚好在
         * 这一刻到期，都应该算退出成功 —— 用户的目的是「让我下线」，
         * 目的已经达到了，没必要报错。
         */
        boolean removed = tokenStore.remove(loginUser.getUserId(), loginUser.getJti());
        log.info("退出登录 | userId={} jti={} 记录存在={}", loginUser.getUserId(),
                loginUser.getJti(), removed);
    }

    /* ==================== 当前用户 ==================== */

    @Override
    public UserVO currentUser() {
        /*
         * ★ 这里不查数据库 —— 直接从 UserContext（ThreadLocal）里拿。
         *
         * 值是由 JwtAuthenticationFilter 在认证成功时写进去的，
         * 而它是从 Redis 取出来的完整 LoginUser（含角色和权限）。
         * 再查一次库纯属浪费，而且会更慢 —— 每次刷新页面都要调这个接口。
         *
         * 这正是大纲 §696 那条约定的实际收益：
         * 「当前用户由 JWT 拦截器写入 UserContext，Service 直接取」。
         */
        LoginUser loginUser = UserContext.get();
        if (loginUser == null) {
            // 同 logout()：正常走不到，属于兜底
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return UserVO.from(loginUser);
    }
}
