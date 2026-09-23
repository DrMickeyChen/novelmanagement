package com.novel.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 自动填充 {@code create_time} / {@code update_time}。
 *
 * <p><b>这个类不加会怎样：</b>实体上虽然写了
 * {@code @TableField(fill = FieldFill.INSERT)}，但 MyBatis-Plus 找不到
 * 填充的实现类，会<b>静默跳过</b> —— 不报错，只是这两个字段一直是 null。
 * 建表语句里虽然给它们设了 {@code DEFAULT CURRENT_TIMESTAMP}，
 * 但 MyBatis-Plus 生成的 INSERT 会显式带上这两列（值为 null），
 * 显式传 null 会让数据库的默认值失效，最终存进去就是 null。
 *
 * <p>表现是：接口不报错，但列表里所有「创建时间」都是空的。
 *
 * <p><b>为什么不让数据库默认值搞定：</b>数据库的 {@code CURRENT_TIMESTAMP}
 * 用的是数据库服务器时区。而这里的 {@code LocalDateTime.now()} 用的是应用时区，
 * 两者在跨时区部署时会不一致。统一由应用填充，时间口径就只有一个。
 *
 * <h3>★★ M4：{@code @ConditionalOnClass} 是给 novel-search 加的</h3>
 *
 * <p><b>★ 这个类比 {@code MybatisPlusConfig} 更早炸，而且更有迷惑性：</b>
 * 它<b>实现了</b> {@code MetaObjectHandler} 这个接口。JVM 加载一个类时要先解析
 * 它的接口和父类 —— 所以只要 search 的 classpath 上没有 mybatis-plus-core，
 * 组件扫描<b>读到这个类的一瞬间</b>就抛 {@code NoClassDefFoundError}，
 * 连「要不要实例化它」都轮不到。
 *
 * <p>★ 计划里我一开始把这一条判成「它只实现接口、不注入 DataSource，大概率不炸」——
 * <b>那是错的</b>，错在只看了「它依赖什么」，没看「它是什么」。
 * 判据应当是<b>「这个类本身能不能被加载」</b>，而不是「它的字段能不能被注入」。
 * 同类隐患：任何 {@code implements} 了第三方库接口的 common 类，都是同一个形状。
 *
 * <p>★ 对原本那四个服务是 no-op（它们本来就有 MyBatis-Plus）。</p>
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@ConditionalOnClass(MetaObjectHandler.class)
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充。
     *
     * <p>{@code strictInsertFill} 的「strict」意思是：<b>字段已经有值就不覆盖</b>。
     * 所以如果你手动 set 了 createTime（比如从旧系统迁移数据、要保留原始时间），
     * 这里不会把它冲掉。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    /**
     * 更新时填充。
     *
     * <p>{@code strictUpdateFill} 同样只在字段为 null 时填充。UPDATE 场景下
     * 这正好是我们想要的：只更新业务字段，updateTime 自动刷新。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
