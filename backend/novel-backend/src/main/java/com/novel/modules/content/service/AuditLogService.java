package com.novel.modules.content.service;

import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.result.PageResult;
import com.novel.modules.content.vo.AuditLogVO;

/**
 * 审核流水服务。
 *
 * <p><b>★★ 写入口只有一个：{@link #record}。而且它只应该被
 * {@code NovelService#transitStatus} / {@code ChapterService#transitStatus} 调用。</b>
 * <p>这个链路是刻意收窄的 —— 想绕开审核日志，就只剩「绕开状态机」一条路，
 * 而状态机是唯一能改状态的入口。<b>在 Controller 里直接调 {@code record}
 * 会让留痕出现缺口</b>，而缺口不报错、不崩溃，只是某天查账时发现少了几条，
 * 那时已经无从补起。
 *
 * <p><b>★ 为什么 {@code record} 的入参里没有 {@code operatorId}：</b>
 * 操作人一律从 {@link com.novel.common.security.UserContext} 取当前登录用户。
 * 让它成为参数的话，调用方就有了「记成别人干的」的能力 ——
 * 而流水表的全部价值就在于「谁做的」这件事不可伪造。
 * <p>代价：将来如果有<b>定时任务</b>（比如二期的对账、自动下架）要写流水，
 * 那时没有请求上下文、{@code UserContext} 是空的。
 * <b>到那时再加一个显式传 operatorId 的重载</b>，
 * 并在注释里写明「只给无请求上下文的场景用」——
 * 现在没有这个场景，就不要预留一个能被误用的口子。
 *
 * @author MickeyChen
 */
public interface AuditLogService {

    /**
     * 记一条审核流水。
     *
     * <p><b>★ 没有事务注解，这是有意的：</b>它默认加入调用方（状态机方法）的事务
     * （Spring 的 {@code REQUIRED} 传播级别）。于是「流水写进去了」和
     * 「状态真的改了」两者要么都成、要么都不成。
     * <p>如果给它单独开一个 {@code REQUIRES_NEW} 事务，就会出现
     * 「状态变更回滚了，日志却留下了」—— 流水里明明白白记着一次通过，
     * 但那本书其实还是待审核。<b>一条不真实的流水比没有流水更糟</b>，
     * 它会让人往错误的方向查。
     *
     * <p><b>★ 调用它之前必须已经确定了状态流转合法</b>（{@code canTransitTo} 返回 true）。
     * 它不校验任何东西 —— 校验在状态机里，这里只负责落笔。
     *
     * @param targetType 审核对象类型（作品 / 章节）
     * @param targetId   对象 id
     * @param action     动作。★ 用 {@code AuditAction}，不是 {@code AuditStatus}
     * @param remark     备注，驳回时是理由，可为 {@code null}
     */
    void record(AuditTargetType targetType, Long targetId, AuditAction action, String remark);

    /**
     * 管理端流水查询，按时间倒序分页。
     *
     * <p><b>★ 写在接口上的第一件事：这个方法【只能读】。</b>
     * 它没有配套的 {@code update} / {@code delete} —— 流水被改掉之后就失去了
     * 追责价值，而「改一条日志」这种需求永远会以「就这一次」的形式出现。
     *
     * <p><b>★ 三个筛选条件都可以为 {@code null}，表示不筛。</b>
     * 最常用的是 {@code action = SUBMIT}（看谁在提交）和
     * {@code operatorId = 某管理员}（查这个人审过什么）。
     *
     * <p><b>★ 结果里每行都会补上书名 / 章节名和操作人昵称</b>
     * （数据库里只存了两个裸 id，只返回 id 的话日志页显示成「作品 #5 通过 操作人 #1」，
     * 完全看不出发生了什么）。补不出来的情况也存在 —— 对象被逻辑删除了 ——
     * 那时 {@code targetTitle} 为空，<b>这是有意接受的，不要为了补它去绕过逻辑删除</b>。
     *
     * @param targetType 对象类型（1作品 2章节 3评论 4作者申请），{@code null} = 全部
     * @param action     动作（1提交 2通过 3驳回 4下架 5重新上架 6退回草稿），{@code null} = 全部
     * @param operatorId 操作人 id，{@code null} = 全部
     */
    PageResult<AuditLogVO> page(AuditTargetType targetType, AuditAction action,
                                Long operatorId, long page, long size);
}
