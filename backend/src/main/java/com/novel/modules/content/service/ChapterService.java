package com.novel.modules.content.service;

import com.novel.common.enums.ChapterStatus;
import com.novel.common.result.PageResult;
import com.novel.modules.content.dto.ChapterCreateDTO;
import com.novel.modules.content.dto.ChapterSaveDTO;
import com.novel.modules.content.vo.ChapterDetailVO;
import com.novel.modules.content.vo.ChapterVO;

import java.util.List;

/**
 * 章节服务。读者端 / 作者端 / 管理端三处共用。
 *
 * <p><b>★ 和 {@code NovelService} 一样，这里没有、也不该有 {@code setStatus}。</b>
 * 状态只能经 {@code ChapterServiceImpl#transitStatus} 改，
 * 对外暴露的是「提交」「审核」「保存」这些<b>业务意图</b>。
 *
 * <p><b>★★ 本服务里有一条比作品更绕的规则，改代码前务必看懂：
 * 「已发布的章节想改正文」会【自动】退回草稿。</b>
 * 大纲的接口表里没有「退回草稿」这个按钮，但已发布的章节必须先退回草稿
 * 才能重新走审核（见 {@code ChapterStatus#canTransitTo}）——
 * 让作者为了改一个错别字去找一个不存在的按钮，等于把人堵死。
 * 所以这个「退回」由 {@link #saveContent} 自己完成，并记一条
 * {@code AuditAction.REVERT} 流水。详见实现里的注释。
 *
 * <p><b>★ 目录的口径有三个，谁都不一样，别搞混：</b>
 * <ul>
 *   <li>读者端 {@link #readerDetail} —— 作品要已上架<b>且</b>章节要已发布</li>
 *   <li>作者端 {@link #authorList} —— 全是我的，<b>全部状态</b></li>
 *   <li>管理端 {@link #adminPage} —— <b>全部状态、跨作品</b>，所以要带书名</li>
 * </ul>
 *
 * @author MickeyChen
 */
public interface ChapterService {

    /* ==================== 作者端 ==================== */

    /**
     * 我某本作品的章节列表（<b>不含正文</b>，含全部状态）。
     *
     * <p>★ 不返回正文 —— 100 章 × 2 万字就是几 MB。这是
     * {@code chapter_content} 单独建表的意义所在（大纲 §1343）。
     *
     * @param novelId 作品 id。<b>会先做归属校验</b> —— 不是自己的作品返回 {@code 2002}
     * @param status  状态筛选（0/1/2/4），{@code null} 表示全部
     */
    List<ChapterVO> authorList(Long novelId, ChapterStatus status);

    /**
     * 新建章节。<b>序号由后端分配</b>（{@code MAX(chapter_no) + 1}），前端传不了。
     *
     * <p><b>★ 没有「作品已上架就不能加章节」这种限制，这是刻意的</b> ——
     * 连载中的作品天天在加新章，那正是它最正常的用法。
     * 「待审核的作品不能编辑」那条规则针对的是<b>作品自己的</b>书名/简介/分类，
     * 章节是独立的审核对象，加章节不会让正在被审的书名悄悄变样。
     *
     * <p>并发下两个请求可能拿到同一个序号 → 唯一键报错 →
     * 抛 {@code CHAPTER_NUMBER_EXISTS(3004)}，由作者重试。
     * <b>刻意不做重试循环</b>，理由见实现里的注释。
     *
     * @param content 可为空 —— 允许「先建章节占位，正文回头再写」
     * @return 新章节的 id
     */
    Long create(Long novelId, ChapterCreateDTO dto);

    /**
     * 章节详情（<b>含正文</b>）。
     *
     * <p>★ 没有这个接口，作者关掉浏览器就再也读不回自己写的正文 —— 等于章节写完不能改。
     * 大纲的接口表里漏了它，是补上的。
     */
    ChapterDetailVO authorDetail(Long id);

    /**
     * 保存章节（标题 + 正文一起）。
     *
     * <p><b>★★ 三种状态三种行为，这是本服务最需要小心的一处：</b>
     * <ul>
     *   <li>{@code DRAFT} / {@code REJECTED} —— 直接保存</li>
     *   <li>{@code PUBLISHED} —— <b>自动退回草稿</b>（记 {@code REVERT} 流水），
     *       并重算作品的章节数/字数，然后保存</li>
     *   <li>{@code PENDING} —— <b>拒绝</b>（{@code 3003}）。
     *       审核员正在看 A 版，作者换成 B 版，审核员点通过 —— 审核被绕过</li>
     * </ul>
     *
     * <p>★ 保存时正文可以是空的（编辑器「全选删除、准备重写」那一步要能存）。
     * 「正文不能为空」的校验在 {@link #submit}，不在保存。
     */
    void saveContent(Long id, ChapterSaveDTO dto);

    /**
     * 提交审核（{@code DRAFT/REJECTED → PENDING}）。
     *
     * <p>★ <b>正文为空时拒绝</b>（{@code CHAPTER_CONTENT_EMPTY}）。
     * 这一条只能放在这里、不能放在保存那里：草稿阶段允许空正文（占位），
     * 但送去审核的空章节，审核员打开只看得到一个标题。
     */
    void submit(Long id);

    /**
     * 删除章节（逻辑删除）。
     *
     * <p><b>★ 走的是手写的 {@code logicDeleteAndFreeChapterNo}，不是 {@code deleteById}。</b>
     * 因为 {@code uk_novel_chapter_no} 不包含 {@code deleted}，
     * 单纯把 {@code deleted} 置 1 会让那一行<b>继续占着序号</b>——
     * 以后再也建不出第 N 章，报 {@code Duplicate entry}。
     * 手写 SQL 顺手把 {@code chapter_no} 改成 {@code -id} 来腾位。
     *
     * <p>★ 删掉的是<b>已发布</b>的章节时，会重算作品的章节数/字数。
     */
    void delete(Long id);

    /* ==================== 管理端 ==================== */

    /**
     * 管理端章节列表（<b>跨作品、不含正文</b>，含全部状态）。
     *
     * <p>最常用的筛选是 {@code status = 1}（待审核）。
     * ★ 返回的每一行都带 {@code novelTitle} —— 列表里混着好几本书的章节，
     * 不显示书名审核员根本不知道该审哪本。
     */
    PageResult<ChapterVO> adminPage(Long novelId, ChapterStatus status, long page, long size);

    /**
     * 管理端章节详情（<b>含正文和驳回理由</b>）。
     *
     * <p>★ 对应大纲 §889 的「弹窗预览正文后通过 / 驳回」。
     * <b>审核员必须能看到正文才能审</b> —— 只看标题点通过等于没审。
     */
    ChapterDetailVO adminDetail(Long id);

    /**
     * 审核章节：通过 → 已发布，驳回 → 已驳回。
     *
     * <p><b>★ 这里【不需要】像 {@code NovelService#audit} 那样前置判断「必须是待审核」。</b>
     * 作品的审核要判，是因为状态机允许 {@code OFFLINE → PUBLISHED}（重新上架），
     * 「审核通过」和「重新上架」在状态机上是同一个流转；
     * 而章节<b>没有「已下架」状态</b>，{@code PUBLISHED} 只能由 {@code PENDING} 到达，
     * {@code REJECTED} 也只能由 {@code PENDING} 到达 —— 状态机本身就已经把它限死了。
     * <p>两处的不对称是<b>状态机本身的不对称</b>造成的，不是漏写。
     */
    void audit(Long id, boolean pass, String remark);

    /* ==================== 读者端 ==================== */

    /**
     * 读者读一章正文 —— <b>阅读器的核心接口</b>。
     *
     * <p><b>★★ 必须同时满足两个条件，少一个都是真实的越权读：</b>
     * <ol>
     *   <li><b>作品已上架</b>（C-7 约束 2）。不查作品、只查章节的话，
     *       作品下架后它的正文<b>照常匿名可读</b> —— 而下架往往正是因为内容有问题。</li>
     *   <li><b>章节已发布</b>，且章节确实属于 URL 上的那本作品
     *       （否则改一下 {@code cid} 就能从一本已上架的书读到另一本未上架的书）。</li>
     * </ol>
     *
     * <p>★ 两种情况都返回 {@code 2001}/{@code 3001} 这类「不存在」，
     * 不区分「未上架」，理由同 {@code NovelService#readerDetail}：
     * 这个接口也免登录，错误码的差别会变成枚举探针。
     *
     * <p>★ <b>不返回 {@code auditRemark}</b> —— 驳回理由是审核内部话术，
     * 泄给读者很荒唐。实现里显式清空。
     */
    ChapterDetailVO readerDetail(Long novelId, Long chapterId);
}
