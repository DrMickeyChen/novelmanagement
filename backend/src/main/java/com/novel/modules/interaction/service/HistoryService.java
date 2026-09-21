package com.novel.modules.interaction.service;

import com.novel.common.result.PageResult;
import com.novel.modules.interaction.dto.HistoryReportDTO;
import com.novel.modules.interaction.vo.ReadHistoryItemVO;
import com.novel.modules.interaction.vo.ReadHistoryVO;

/**
 * 阅读进度服务。
 *
 * <p><b>★★ 这个接口和 {@code NovelService} 是分开的两个「模块」，不是同一个类里
 * 多两个方法。</b>包结构按二期要拆的服务划分（大纲 §178）：
 * <pre>
 *   modules/system      ↔  novel-auth
 *   modules/content     ↔  novel-content
 *   modules/interaction ↔  novel-interaction   ← 本包，P3 新建
 * </pre>
 * 阅读进度、书架、评论、点赞都是「用户对内容产生的行为」，和内容本身是两类数据：
 * 内容库要备份、要审核、读多写少；行为库写多、可以丢、按用户分片。
 * P6 的书架和评论会直接加进这个包，而不是塞进 content。
 *
 * <p><b>★ 依赖方向是 {@code interaction → content}，只此一个方向。</b>
 * 本模块要校验「这本书已上架」「这一章属于这本书」，
 * 用的是 {@code NovelService#requirePublished} 和
 * {@code NovelAccessGuard#requireChapterInNovel}。
 * <p>二期 M3 把 interaction 拆出去时，这两个调用会变成 Feign 调用 ——
 * 而 {@code ContentUserLookup} 当初就是为了让这种跨模块调用
 * 「只有一个出口」才抽出来的，这里是同一个思路的第二次应用。
 * <p><b>反过来绝对不行</b>：{@code content} 里不能出现任何对
 * {@code HistoryService} 的依赖，否则两个服务互相依赖，拆分顺序直接作废。
 *
 * @author MickeyChen
 */
public interface HistoryService {

    /**
     * 上报阅读进度。同一 {@code (用户, 作品)} 只保留一条，覆盖写入。
     *
     * <p><b>★★ 校验顺序（四步，一步都不能省，且顺序不能换）：</b>
     * <ol>
     *   <li>作品存在<b>且已上架</b>，否则 {@code NOVEL_NOT_FOUND(2001)}；</li>
     *   <li>章节存在<b>且属于这本作品</b>，否则 {@code CHAPTER_NOT_FOUND(3001)}；</li>
     *   <li>章节<b>已发布</b>，否则 {@code CHAPTER_NOT_FOUND(3001)}；</li>
     *   <li>{@code progress} 夹到 {@code [0, 100]}。</li>
     * </ol>
     *
     * <p><b>★ 为什么这四步和 {@code ChapterServiceImpl#readerDetail} 的校验完全一致
     * 是刻意的，不是抄漏了：</b>
     * 上报的进度必须指向一个<b>读者本来就能读到的位置</b>。
     * 少了第 1 步，可以往一本未上架的书里写进度（数据被污染，
     * 而那本书上架后读者会莫名其妙地「上次读到第 3 章」）；
     * 少了第 3 步，可以拿这个接口当探针，用「上报成功 / 失败」
     * 反推出一本未上架的书里有哪些章节 id —— <b>这就是一条内容泄露通道</b>，
     * 而且它是个 POST，看起来人畜无害。
     *
     * <p><b>★ 关于第 2 步（章节属于作品）：</b>只按 {@code chapterId} 查的话，
     * 把 id 换成别的书的章节就能上报成功，URL / body 里的 {@code novelId}
     * 形同虚设 —— 于是进度会被写到一本和用户实际在读的完全无关的书上。
     *
     * @throws com.novel.common.exception.BusinessException 见上面的顺序
     */
    void report(HistoryReportDTO dto);

    /**
     * 取当前用户在某本书上的进度。<b>没有记录返回 {@code null}，这不是错误。</b>
     *
     * <p><b>★ 返回值允许为 {@code null}，接口文档里写的 {@code data: null} 就是这个意思。</b>
     * 全局配了 {@code spring.jackson.default-property-inclusion: non_null}，
     * 所以 {@code data} 这个 key 在 JSON 里<b>会整个消失</b>而不是显示成 {@code null}。
     * 前端写 {@code res.data ?? null} 即可，两种表现对它是一样的。
     *
     * <p><b>★★ 这里不校验作品是否存在 / 是否上架，这是刻意的。</b>
     * 本方法只可能返回<b>调用者自己的</b>那一行 —— 作品 id 再乱传也扫不出别人的数据，
     * 没有枚举风险。反过来，如果作品刚被下架就返回 2001，
     * 读者会看到一条「作品不存在」的错误弹窗，而他只想知道自己读到哪了。
     * 「查不到就是没有进度」对调用方是最简单、也最不会出错的口径。
     *
     * <p><b>★ {@code userId} 来自 {@code UserContext}，不来自入参。</b>
     * 这是这个接口唯一的安全边界：没有它，{@code GET
     * /api/reader/history/{novelId}} 就成了「读任意用户的阅读进度」（IDOR）。
     *
     * @param novelId 作品 id
     * @return 进度，或 {@code null}（该用户在这本书上没有记录）
     */
    ReadHistoryVO get(Long novelId);

    /**
     * 我的阅读历史，按最后阅读时间倒序。
     *
     * <p><b>★★ 和 {@link #get} 是两条接口、两个 VO，刻意不合并。</b>
     * 这个判断是老板写在 {@code ReadHistoryVO} 类注释里的：
     * 阅读器每次上报进度都会调 {@code get()}，如果为了列表页给它加上
     * 书名 / 封面 / 章名，那三个字段就是<b>每本书一次查询</b>的开销，
     * 而阅读器一个都用不上。列表页一次才查一页，成本结构完全不同。
     * <p>所以这里是 {@code ReadHistoryItemVO}（带书名/封面/章名），
     * 而 {@code ReadHistoryVO} <b>一个字节都不动</b>。
     *
     * <p><b>★ 不做「删除历史」</b>：{@code read_history} 表没有 {@code deleted} 列，
     * 要做就只能物理删，而「误删历史」不可恢复；大纲也没有要求。
     *
     * <p>★ 每行带 {@code available}（作品是否还读得到）和进度。
     * 「这本书下架了」不能把这一行从历史里抹掉 —— 读者会以为记录丢了。
     */
    PageResult<ReadHistoryItemVO> myPage(long page, long size);
}
