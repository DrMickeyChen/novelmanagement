package com.novel.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.ai.entity.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 违禁词库 Mapper（E1/S7）。
 *
 * <p>★ 与 {@code AiPromptTemplateMapper} 同形：只继承 {@link BaseMapper}，
 * 一条手写 SQL 都没有。★ 少写手写 {@code @Select} 有一个具体的技术收益：
 * {@code @TableLogic} 对手写 SQL 完全不生效，而本表压根没有逻辑删除列 ——
 * 手写 SQL 的人很容易顺手补一个 {@code deleted = 0}，那是个<b>列不存在</b>的运行时错误。
 *
 * <p>★★ 本表被读得<b>很频繁</b>（每次 reload 一次全表，每 10 分钟一轮），
 * 但读的是「所有 {@code enabled=1} 的行」这一种形状 —— 走 {@code idx_enabled}。
 * <b>不要为它加缓存或分发式协调</b>：每实例一份进程内快照就是全部机制，
 * 理由见 {@code SensitiveWordScanner} 的类注释。
 *
 * @author MickeyChen
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {
}
