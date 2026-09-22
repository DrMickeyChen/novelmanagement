package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.SysNotice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公告 Mapper。
 *
 * <p><b>★ 和 {@code AuthorApplyMapper} / {@code NovelMapper} 一样，
 * 一条手写 SQL 都没有，这是刻意的。</b>
 * 公告的全部查询（按状态筛、按标题搜、按 id 取单条）都能用
 * {@code LambdaQueryWrapper} 在 Service 层拼出来，而 Wrapper 的好处是
 * {@code @TableLogic} 会自动补 {@code deleted = 0} ——
 * 一旦手写成 {@code @Select}，这个保护立刻消失，得自己记得写。
 *
 * <p><b>为什么这里没有「按发布时间取前 N 条」的手写 SQL：</b>
 * 读者端要的是「已发布 + 按 publish_time 倒序 + 取前 5 条」，
 * 这件事用 {@code wrapper.last("LIMIT 5")} 就够了。
 * 单独写一条 {@code @Select} 的话，就会多出一份「忘了写 deleted = 0」的机会，
 * 而它的症状是「删掉的公告还挂在读者端首页上」——
 * 一个只在删除之后才出现、且看起来完全正常的 bug。
 *
 * @author MickeyChen
 */
@Mapper
public interface SysNoticeMapper extends BaseMapper<SysNotice> {
}
