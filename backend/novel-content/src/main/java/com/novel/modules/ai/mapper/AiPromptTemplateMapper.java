package com.novel.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.ai.entity.AiPromptTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提示词模板 Mapper（E1）。
 *
 * <p>★ 只继承 {@link BaseMapper}，一条手写 SQL 都没有 —— 模板的查询全是
 * 「按 scene 查」这种单表条件查询，{@code LambdaQueryWrapper} 足够。
 * <p>★★ 少写手写 {@code @Select} 还有一个具体收益：{@code @TableLogic} 对手写 SQL 完全不生效，
 * 而本表压根没有逻辑删除列（见实体注释）—— 手写 SQL 的人很容易顺手补一个
 * {@code deleted = 0}，那是个<b>列不存在</b>的运行时错误。
 *
 * <p>★ 不需要补 {@code @MapperScan}：{@code ContentApplication} 上写的是
 * {@code com.novel.modules.**.mapper}，{@code com.novel.modules.ai.mapper} 已被覆盖 ——
 * 这正是 M2 把它写成通配（而不是列一堆包名）换来的，E1 加模块零配置改动。
 *
 * @author MickeyChen
 */
@Mapper
public interface AiPromptTemplateMapper extends BaseMapper<AiPromptTemplate> {
}
