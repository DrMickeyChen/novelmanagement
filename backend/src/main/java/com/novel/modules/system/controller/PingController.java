package com.novel.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 连通性检测接口 —— P0 阶段的验收入口。
 *
 * <p><b>这三个接口是故意分开的，不要合并成一个：</b>
 * 后端启动流程里，应用起来和连上数据库是两件事。
 * 如果只有一个「全都要」的检测接口，那数据库连不上时你也看到 500，
 * 就无法判断到底是应用没起来，还是只是数据库连不上。
 * 分开之后排查路径就很清晰：
 * <ul>
 *   <li>{@code /api/ping} 不通 → 应用本身没启动</li>
 *   <li>{@code /api/ping} 通、{@code /api/ping/db} 不通 → 数据库/网络问题</li>
 *   <li>两个都通、{@code /api/ping/redis} 不通 → Redis 配置问题</li>
 * </ul>
 *
 * <p><b>它们不需要登录就能访问</b>，所以将来加 Spring Security 时，
 * 记得把 {@code /api/ping/**} 放进白名单。
 *
 * @author MickeyChen
 */
@Slf4j
@Tag(name = "00. 连通性检测", description = "P0 阶段验收用：确认应用、MySQL、Redis 是否都能连通")
@RestController
@RequestMapping("/api/ping")
@RequiredArgsConstructor
public class PingController {

    private final SysUserMapper sysUserMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 探针用的 Redis key。用固定的、带业务前缀的名字，
     * 这样在 RedisInsight 里一眼能看出这是个体检用的键，不是真实数据。
     */
    private static final String PROBE_KEY = "novel:ping:probe";

    /* ================================================================
     *  1. 应用存活 —— 完全不碰数据库和 Redis
     * ================================================================ */

    @Operation(
            summary = "应用存活检测",
            description = "只证明 Spring 容器起来了、能处理 HTTP 请求。不访问任何外部依赖。")
    @GetMapping
    public Result<Map<String, Object>> ping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("application", "novel-backend");
        data.put("time", LocalDateTime.now());
        data.put("message", "后端服务正常运行");

        log.debug("收到 ping 请求");
        return Result.success(data);
    }

    /* ================================================================
     *  2. 数据库连通性 —— 真查一次库
     * ================================================================ */

    @Operation(
            summary = "MySQL 连通性检测",
            description = """
                    真的去查一次 sys_user 表，证明「IDEA → 虚拟机 MySQL → novel 库 → 表里有数据」整条链路是通的。

                    返回的 total 应该是 1（种子数据里的 admin），
                    如果 total = 0 说明 seed.sql 没执行成功。""")
    @GetMapping("/db")
    public Result<Map<String, Object>> pingDb() {
        try {
            // ① 查总数。null 表示没有额外条件，MyBatis-Plus 会自动加上 deleted = 0
            Long total = sysUserMapper.selectCount(null);

            // ② 查种子数据里的 admin，验证具体字段能读出来
            SysUser admin = sysUserMapper.selectOne(
                    new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getUsername, "admin")
                            .last("LIMIT 1"));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "UP");
            data.put("database", "novel");
            data.put("userCount", total);

            if (admin != null) {
                /*
                 * ★ 这里只挑安全字段返回，绝不把整个 SysUser 对象丢出去。
                 *
                 * 直接 return admin 的话，password 字段（BCrypt 密文）会一起
                 * 序列化到 JSON 里返回给前端。密文虽然解不回明文，但拿去离线暴力
                 * 破解是常见攻击手段 —— 运维排查接口泄露密码哈希，是个真实发生过的事故。
                 */
                Map<String, Object> adminInfo = new LinkedHashMap<>();
                adminInfo.put("id", admin.getId());
                adminInfo.put("username", admin.getUsername());
                adminInfo.put("nickname", admin.getNickname());
                adminInfo.put("status", admin.getStatus());
                adminInfo.put("createTime", admin.getCreateTime());
                data.put("admin", adminInfo);
            } else {
                data.put("admin", null);
                data.put("warning", "没查到 admin 用户，请确认 docs/sql/seed.sql 是否执行成功");
            }

            data.put("message", "MySQL 连接正常");
            return Result.success(data);

        } catch (Exception e) {
            /*
             * 这个接口是拿来排查问题的，所以不能让它走到全局兜底
             * 返回一句笼统的「服务器开小差了」—— 那等于白查。
             * 这里把真实的失败原因说出来（只给开发者看，所以可以细）。
             */
            log.error("MySQL 连通性检测失败", e);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "DOWN");
            data.put("message", "MySQL 连接失败");
            data.put("reason", rootCauseMessage(e));
            data.put("hint", "检查 application-dev.yml 里的 IP 是否为虚拟机 IP；"
                    + "虚拟机上执行 `docker compose ps` 看 novel-mysql 是否 Up (healthy)");
            return Result.error(ResultCode.SYSTEM_ERROR, "MySQL 连接失败，详见 data 字段");
        }
    }

    /* ================================================================
     *  3. Redis 连通性 —— 写进去再读出来
     * ================================================================ */

    @Operation(
            summary = "Redis 连通性检测",
            description = """
                    往 Redis 写一个对象再读回来，同时验证两件事：
                    ① 网络和密码没问题；② JSON 序列化配置生效（读回来的值应该是完整的，不是 null）。""")
    @GetMapping("/redis")
    public Result<Map<String, Object>> pingRedis() {
        try {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("from", "novel-backend");
            probe.put("at", LocalDateTime.now().toString());
            probe.put("roundTrip", true);

            // 写，60 秒后自动过期 —— 就算这个接口被人反复调用也不会在 Redis 里堆垃圾
            redisTemplate.opsForValue().set(PROBE_KEY, probe, 60, TimeUnit.SECONDS);

            // 读回来，验证序列化没把对象搞丢
            Object readBack = redisTemplate.opsForValue().get(PROBE_KEY);

            boolean serializationOk = readBack instanceof Map<?, ?> map
                    && Boolean.TRUE.equals(map.get("roundTrip"));

            // 验证完就删掉，不留痕迹
            redisTemplate.delete(PROBE_KEY);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "UP");
            data.put("readBack", readBack);
            data.put("serializationOk", serializationOk);
            data.put("message", serializationOk
                    ? "Redis 连接正常，JSON 序列化正常"
                    : "Redis 能连上，但读回来的值和写进去的不一样，请检查 RedisConfig");

            return Result.success(data);

        } catch (Exception e) {
            log.error("Redis 连通性检测失败", e);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "DOWN");
            data.put("message", "Redis 连接失败");
            data.put("reason", rootCauseMessage(e));
            data.put("hint", "检查 application-dev.yml 里 spring.data.redis 的 host 和 password；"
                    + "注意 Spring Boot 3 的配置路径是 spring.data.redis，不是 spring.redis");
            return Result.error(ResultCode.SYSTEM_ERROR, "Redis 连接失败，详见 data 字段");
        }
    }

    /* ================================================================
     *  私有工具
     * ================================================================ */

    /**
     * 取最底层的异常原因。
     *
     * <p>Java 的异常经常层层包装，比如这里实际会看到
     * {@code DataAccessResourceFailureException → CannotGetJdbcConnectionException
     * → CommunicationsException → ConnectException}。
     * 最外层那句「Failed to obtain JDBC Connection」完全没有信息量，
     * 真正有用的是最里面那句 {@code Connection refused}。
     */
    private String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
