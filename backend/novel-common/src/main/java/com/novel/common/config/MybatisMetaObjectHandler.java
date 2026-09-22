package com.novel.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
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
 * @author MickeyChen
 */
@Slf4j
@Component
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
