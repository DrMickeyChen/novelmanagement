package com.novel.modules.system.service;

import com.novel.modules.system.dto.LoginDTO;
import com.novel.modules.system.dto.RegisterDTO;
import com.novel.modules.system.vo.LoginVO;
import com.novel.modules.system.vo.UserVO;

/**
 * 认证服务。
 *
 * <p><b>这是本项目的第一个 Service —— 顺便把分层约定立起来：</b>
 * <pre>
 *   Controller 只做三件事：接参数、调 Service、包 Result
 *   Service    放业务规则和事务边界（@Transactional 标在这里）
 *   Mapper     只管取数，不含 if-else 的业务判断
 * </pre>
 * 为什么不让 Controller 直接调 Mapper（P0 阶段的 {@code PingController} 就是那样，
 * 因为那是探针）：业务规则一旦写进 Controller，就没法被别的入口复用，
 * 也<b>没法被单元测试单独测</b>（得把整个 MVC 环境搭起来）。
 * 第二期拆微服务时，Service 接口基本原样保留，只换实现。
 *
 * <p>定义成接口 + 实现类（而不是直接写一个类），是为第二期留的口子：
 * 拆分后 {@code AuthService} 会变成 Feign 客户端，其他服务只依赖接口，
 * 不用改调用方的代码。
 *
 * @author MickeyChen
 */
public interface AuthService {

    /**
     * 登录。
     *
     * @return token + 用户信息
     * @throws com.novel.common.exception.BusinessException
     *         用户名或密码错误 {@code 1002} / 账号被禁用 {@code 1003}
     */
    LoginVO login(LoginDTO dto);

    /**
     * 注册。新用户固定授予 {@code READER} 角色。
     *
     * @throws com.novel.common.exception.BusinessException 用户名已存在 {@code 1004}
     */
    UserVO register(RegisterDTO dto);

    /**
     * 退出登录 —— 只退出<b>当前这台设备</b>。
     *
     * <p>同一账号在别的设备上的登录态不受影响（Redis key 里带了 jti，
     * 见 {@code RedisKeyConstant.AUTH_TOKEN}）。
     */
    void logout();

    /**
     * 取当前登录用户。
     *
     * <p>数据直接来自 {@code UserContext}（拦截器已从 Redis 取好），
     * 不再查库。
     */
    UserVO currentUser();
}
