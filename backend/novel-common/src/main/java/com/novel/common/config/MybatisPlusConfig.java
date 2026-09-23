package com.novel.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p><b>注意：插件的顺序不能乱。</b>MyBatis-Plus 是<b>按添加顺序依次套用</b>的，
 * 分页插件必须放在最后 —— 因为它要改写 SQL 加 LIMIT，
 * 如果先分页再让 BlockAttack 去检查，检查的就不是最终 SQL 了。
 *
 * <h3>★★ M4：{@code @ConditionalOnClass} 是给 novel-search 加的</h3>
 *
 * <p>这个类一直被四个服务无差别扫到，从没出过问题 —— 因为那四个服务都连数据库，
 * classpath 上都有 MyBatis-Plus。<b>M4 的 {@code novel-search} 是第一个不连库的服务</b>
 * （它只读写 ES，数据靠 Feign 从 content 取），于是它成了第一个会在这里炸的。
 *
 * <p><b>★ 炸法不是「少个插件」，是启动直接失败。</b>这个 {@code @Bean} 方法的返回类型
 * {@code MybatisPlusInterceptor} 来自 {@code mybatis-plus-extension} ——
 * 那个 jar 不在 search 的 classpath 上，Spring 调不到这个方法，
 * 报的是 {@code NoClassDefFoundError}，栈里指着一堆 MyBatis-Plus 的类名。
 * ★★ 而它看上去很像「依赖冲突」，不像「你有一个服务不该有这个类」。
 *
 * <p><b>★ 为什么用 {@code @ConditionalOnClass} 而不是 {@code @ConditionalOnBean(DataSource.class)}：</b>
 * <ul>
 *   <li>{@code @ConditionalOnClass} 判断的是<b>类路径</b>，与 bean 的注册顺序无关，
 *       结论稳定。</li>
 *   <li>{@code @ConditionalOnBean} 判断的是<b>「此刻容器里有没有这个 bean」</b>——
 *       而「此刻」取决于评估顺序。组件扫描出来的 {@code @Configuration}
 *       常常<b>早于</b>自动配置跑，那时数据源 bean 还没注册，
 *       条件为假、配置被跳过 —— <b>而且是静默跳过</b>，四个服务谁都没察觉。
 *       ★ 那会变成一条「现在能跑、将来某个版本一动就坏」的隐式依赖。</li>
 * </ul>
 * <p>★ 判断「该用哪个」的准则是：<b>条件成立与否取决于【有没有这个库】时用
 * {@code @ConditionalOnClass}；取决于【别人配没配】时用 {@code @ConditionalOnProperty}。</b>
 * 本文件是前者，同目录的 {@code UploadProperties} 是后者，两个对照着读。
 *
 * <p>★ 对原本那四个服务是 <b>no-op</b>（它们本来就满足条件）——
 * 这一点由 M4 的 S2 探针实测：四个服务必须照样起得来。</p>
 *
 * @author MickeyChen
 */
@Configuration
@ConditionalOnClass(MybatisPlusInterceptor.class)
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
