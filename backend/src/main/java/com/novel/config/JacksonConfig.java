package com.novel.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 时间格式配置。
 *
 * <p><b>★ 为什么必须单独写这个类，`spring.jackson.date-format` 不够用：</b>
 *
 * <p>很多人（包括我第一版）会在 {@code application.yml} 里写：
 * <pre>
 * spring:
 *   jackson:
 *     date-format: yyyy-MM-dd HH:mm:ss
 * </pre>
 * 然后发现<b>完全没生效</b> —— 时间还是输出成 {@code "2026-09-19T23:56:13.2551421"}。
 *
 * <p>原因是 {@code date-format} 这个配置项<b>只对老的 {@code java.util.Date} 有效</b>。
 * 而实体里用的是 {@code java.time.LocalDateTime}，它由 Jackson 的
 * JavaTimeModule 处理，走的是完全另一条路径，压根不看那个配置。
 * 最坑的是：<b>不报错、不警告，就是不生效</b>，只能靠肉眼看接口返回才发现。
 *
 * <p>正确做法就是这里 —— 直接给 {@code LocalDateTime} 这个类型
 * 注册指定的序列化器/反序列化器，一劳永逸，不用在每个字段上贴
 * {@code @JsonFormat}。
 *
 * <p><b>序列化和反序列化要成对配置：</b>只配 serializer 的话，
 * 前端把 {@code "2026-09-19 23:56:13"} 传回来时，默认的反序列化器
 * 只认 ISO 格式（带 T 的那种），会直接报格式错误。
 *
 * @author MickeyChen
 */
@Configuration
public class JacksonConfig {

    /** 日期时间格式，前后端约定的统一格式 */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 纯日期，例：生日、上架日期 */
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 纯时间，例：每日更新时间点 */
    private static final String TIME_PATTERN = "HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN);

        return builder -> {
            // ---------- LocalDateTime：2026-09-19 23:56:13 ----------
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
            builder.deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));

            // ---------- LocalDate：2026-09-19 ----------
            builder.serializerByType(LocalDate.class, new LocalDateSerializer(dateFormatter));
            builder.deserializerByType(LocalDate.class, new LocalDateDeserializer(dateFormatter));

            // ---------- LocalTime：23:56:13 ----------
            builder.serializerByType(LocalTime.class, new LocalTimeSerializer(timeFormatter));
            builder.deserializerByType(LocalTime.class, new LocalTimeDeserializer(timeFormatter));

            /*
             * ============================================================
             * 【记一笔待办，第二期再决定】
             * ============================================================
             * JavaScript 的 Number 是双精度浮点，能精确表示的整数上限是
             * 2^53 - 1（9007199254740991，16 位）。第二期微服务如果换成
             * 雪花算法生成 19 位 ID，传到前端会被悄悄改掉末尾几位 ——
             * 表现是「详情页打不开，提示 ID 不存在」，但数据库里明明查得到。
             *
             * 到时候的解法是加这一行，把 Long 统一序列化成字符串：
             *     builder.serializerByType(Long.class, ToStringSerializer.instance);
             *
             * ★ 现在不加，是因为它会【连分页字段一起改掉】——
             * PageResult 的 total / current / size / pages 都是 Long，
             * 加了之后接口会返回 "total": "137" 而不是 "total": 137，
             * Element Plus 的分页组件对接时会出问题。
             *
             * 本期主键是数据库自增（AUTO_INCREMENT），位数远达不到精度上限，
             * 加了只有副作用没有收益。等真的需要雪花 ID 时，
             * 再连带把分页字段的类型一起调整 —— 那是个需要前后端一起改的决定，
             * 不该顺手塞进 P0。
             */
        };
    }
}
