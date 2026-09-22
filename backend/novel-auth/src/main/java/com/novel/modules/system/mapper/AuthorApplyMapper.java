package com.novel.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.system.entity.AuthorApply;
import org.apache.ibatis.annotations.Mapper;

/**
 * 作者申请表 Mapper。
 *
 * <p><b>和 {@code NovelMapper} 一样，一条手写 SQL 都没有，这是刻意的。</b>
 * 申请的全部查询（按状态筛、按笔名搜、按 userId 查自己那份、按 id 取单条）
 * 都能用 {@code LambdaQueryWrapper} 在 Service 层拼出来，
 * 而 Wrapper 的好处是 {@code @TableLogic} 会自动补 {@code deleted = 0}。
 * 一旦手写成 {@code @Select}，这个保护就没了，得自己记得写。
 *
 * <p><b>★ 别在这里加「按申请人昵称搜」的 join。</b>
 * 那件事在 {@code AuthorApplyServiceImpl} 里用两次查询拼出来
 * （先查 {@code sys_user} 拿 id、再 {@code IN} 过滤），
 * 理由是那样能复用 {@code @TableLogic}，而手写 join 必须自己写两个
 * {@code deleted = 0}（{@code author_apply} 和 {@code sys_user} 各一个），
 * 漏掉任何一个都不会报错，只会让已注销用户的申请混进列表。
 *
 * @author MickeyChen
 */
@Mapper
public interface AuthorApplyMapper extends BaseMapper<AuthorApply> {
}
