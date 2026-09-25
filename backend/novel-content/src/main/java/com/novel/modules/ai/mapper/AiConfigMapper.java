package com.novel.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.ai.entity.AiConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 配置 Mapper（E1）。
 *
 * <p>★ 一个手写 SQL 都没有 —— 全部走 {@code BaseMapper}。
 * 全局 {@code @MapperScan("com.novel.modules.**.mapper")} 在 {@code ContentApplication} 上，
 * 这个包已经被覆盖（同 {@code AiPromptTemplateMapper}）。
 *
 * <p>★★ <b>本 Mapper 有一个必须守住的纪律：绝不给 {@code api_key} 写 {@code @Select} 投影。</b>
 * 理由是 {@code AiConfig} 的类注释那条 —— 谁能拿到 {@code getApiKey()}，
 * 谁就可能在没解密的情况下把它当明文用。这个类保持「零手写 SQL」，
 * 是为了让「有没有人绕过去查密文」这件事<b>一眼可查</b>（整个文件就这几行）。
 *
 * @author MickeyChen
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfig> {
}
