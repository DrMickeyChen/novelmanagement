package com.novel.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p><b>注意：插件的顺序不能乱。</b>MyBatis-Plus 是<b>按添加顺序依次套用</b>的，
 * 分页插件必须放在最后 —— 因为它要改写 SQL 加 LIMIT，
 * 如果先分页再让 BlockAttack 去检查，检查的就不是最终 SQL 了。
 *
 * @author MickeyChen
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页 + 乐观锁 + 防全表操作，三个插件一次注册。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        /*
         * 乐观锁插件。
         * 用法：实体里加 @Version 注解的 Integer 字段，建表语句里也要有 version 列。
         * 更新时 MyBatis-Plus 会改成：
         *     UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?
         * 影响行数为 0 就说明被别人抢先改了，业务层要提示「数据已变更，请刷新重试」。
         *
         * 第二期拆微服务后这个插件尤其重要 —— 多个服务同时改同一条数据时，
         * 它是成本最低的并发保护（比分布式锁轻得多）。
         */
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        /*
         * ★ 防全表更新/删除插件。
         *
         * 它会拦住【没有 WHERE 条件】的 UPDATE 和 DELETE，直接抛异常。
         * 没有它的话，一句 mapper.delete(null) 或者 wrapper 拼错，
         * 就能把整张表清空 —— 而且这种事故在测试环境根本发现不了，
         * 通常要到线上才炸。
         */
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        /*
         * 分页插件。★ 必须放最后。
         *
         * setMaxLimit 是硬上限：前端传 size=999999 也会被压到 100，
         * 防止有人用一次请求把整张表拖出来。
         */
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false);   // 页码超过总页数时返回空列表，而不是回到第一页
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
