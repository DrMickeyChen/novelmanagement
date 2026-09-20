package com.novel.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.content.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核日志 Mapper。
 *
 * <p><b>★ 这张表只 INSERT 和查，永远不 UPDATE、不 DELETE</b>（见 {@link AuditLog} 的类注释）。
 * 所以 {@code BaseMapper} 白送的那 18 个方法里，实际只用到
 * {@code insert} / {@code selectPage}。
 *
 * <p>查询按 {@code target_type} / {@code action} / {@code operator_id} 筛 ——
 * 这三个条件在 {@code schema.sql} 里都有对应的索引，因为「按对象查它的历史」
 * 和「按人查他做过什么」是审核日志仅有的两种用法。
 *
 * @author MickeyChen
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
