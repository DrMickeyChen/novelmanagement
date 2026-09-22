package com.novel.common.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置。
 *
 * <p><b>不配这个会怎样：</b>Spring Boot 默认给 {@code RedisTemplate} 用的是
 * JDK 序列化（{@code JdkSerializationRedisSerializer}）。结果是：
 * 用 redis-cli 打开一看，value 是一串乱码，形如
 * {@code \xac\xed\x00\x05sr\x00\x13com.novel...} ——
 * 完全没法人工排查，而且换个语言的服务（比如第二期可能加个 Python 脚本）就读不了。
 *
 * <p>改成 JSON 之后，Redis 里存的就是
 * {@code {"@class":"com.novel.modules.novel.entity.Novel","id":1,...}}，
 * 肉眼可读、跨语言可用。
 *
 * @author MickeyChen
 */
@Configuration
public class RedisConfig {

    /**
     * 业务用 RedisTemplate：key 是字符串，value 是 JSON。
     *
     * <p>{@code StringRedisTemplate} Spring Boot 已经自动配好了（存纯字符串用），
     * 这里额外定义一个能存对象的，两个可以共存，不要覆盖默认的。
     *
     * <h3>★★ 需要 {@code INCR} / {@code SET NX} / {@code SADD} 时必须走
     * {@code StringRedisTemplate}，不能走这个 bean</h3>
     *
     * <p>下面那个 {@code GenericJackson2JsonRedisSerializer} 会把数字 {@code 1}
     * 写成 {@code {"@class":"java.lang.Integer","value":1}}，于是：
     * <ul>
     *   <li>{@code INCR} 直接报 {@code ERR value is not an integer or out of range}
     *       —— Redis 的 INCR 要求 value 是十进制数字字符串，不是一坨 JSON；</li>
     *   <li>{@code SET NX} 本身能跑（它只看 key 在不在），但读回来的是那坨 JSON，
     *       于是「值等于 1」这种判断会莫名其妙地不成立；</li>
     *   <li>{@code SADD} 存进去的成员也是带 {@code "@class"} 的 JSON，
     *       用 {@code SISMEMBER} 拿一个纯字符串去查永远查不到。</li>
     * </ul>
     *
     * <p>★ <b>这条论证原来写在 {@code NovelViewCounter} 的类注释里</b>，
     * M3 删掉那个类时搬到了这里。搬家的理由：它讲的是
     * <b>这两个 Redis bean 的区别</b>，不是某一段业务逻辑 ——
     * 留在业务类里就会跟着业务一起被删掉，而它跟业务无关。
     *
     * <p>★ 目前真实的使用者是 interaction 的 {@code CommentServiceImpl}
     * （评论 10 秒频控的 {@code SET NX EX}，以及点赞去重的 {@code SADD}）——
     * 阅读量从 M3 起不再用 Redis 了，但这条规矩对将来任何计数器都成立
     * （比如 E1 的 AI 每日额度）。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildObjectMapper());

        // key 和 hash 的 field 用纯字符串 —— 这样 keys 命令出来是能看懂的
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        // value 用 JSON
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 给 Redis 用的 ObjectMapper。
     *
     * <p>和 Spring MVC 那个 {@code ObjectMapper} 是<b>两个独立实例</b>，
     * 互不影响 —— 这里开启多态类型信息不会污染接口返回的 JSON
     * （接口返回里绝不能出现 {@code @class} 字段，那会泄露内部类结构）。
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 支持 LocalDateTime / LocalDate（不加这个，一存时间就抛
        // InvalidDefinitionException: Java 8 date/time type not supported）
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 让 private 字段也能被序列化，不依赖 getter
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        /*
         * ★ 写入类型信息，这样从 Redis 读出来能还原成原来的类。
         *
         * 不加的话，存进去的 Novel 对象取出来会变成 LinkedHashMap，
         * 强转就 ClassCastException。
         *
         * ★ 这里没有用网上常见的 LaissezFaireSubTypeValidator（放行一切），
         * 而是用白名单 —— 因为「反序列化时按 JSON 里的类名去加载类」是个
         * 经典的攻击面（Jackson 反序列化漏洞）：如果 Redis 被写入
         * 恶意构造的 @class 指向某个危险类，放行一切就会直接执行。
         * 白名单把可还原的类型限制在自己项目 + 基础类型里。
         */
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.novel.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .build();

        mapper.activateDefaultTyping(
                validator,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return mapper;
    }
}
