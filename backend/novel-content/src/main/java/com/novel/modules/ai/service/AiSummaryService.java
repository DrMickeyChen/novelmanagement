package com.novel.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.UserContext;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.dto.AiSummaryGenerateDTO;
import com.novel.modules.ai.entity.AiSummary;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.mapper.AiSummaryMapper;
import com.novel.modules.ai.vo.AiSummaryVO;
import com.novel.modules.ai.vo.RenderedPromptVO;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.ChapterContent;
import com.novel.modules.content.mapper.ChapterContentMapper;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.service.NovelAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 前情摘要的<b>写</b>侧（E1/S10）—— 作者手动触发的一次压缩。
 *
 * <h3>★★★ 它和「读侧」是同一件事的两半，而两半是【解耦】的</h3>
 *
 * <p>读侧早在 S6 就完成了：生成时 {@link AiContextAssembler} 会按 {@code novelId}
 * 取 {@code ai_summary} 里 {@code end_chapter_no} 最大的一条填进 {@code {{summary}}}。
 * S10 只补写侧 —— <b>而这就是判断 2「③ 与 ⑤ 解耦」的兑现方式</b>：
 * 本类上线前后，<b>渲染侧一行代码都没改</b>，同一份模板从
 * 「{@code {{summary}}} 无值 → 整行移除」自动变成「带上摘要」。
 * S10 的验收就是抓这两个状态做差分（见 {@code docs/E1-AI能力接入.md}）。
 *
 * <p>★ 换一个说法：这个类没有「让下一章知道前情」的代码，它只是往表里插了一行。
 * 读侧怎么找到它、怎么渲染它，全在别处。<b>这就是「判据钉在结构上」的收益</b> ——
 * 如果摘要的消费方式是「调用方自己拼进 prompt」，那每加一个消费点就要改一次组装器。
 *
 * <h3>★★★ 为什么它是【同步】的（本方法会占住请求线程整整一次模型调用）</h3>
 *
 * <p>四个流式场景（大纲/正文/灵感/润色）走 SSE，因为产出要<b>边生成边看</b> ——
 * 作者要盯着字一个个出来，觉得不对就中断。摘要不是：<b>它的产出直接落库，
 * 没有「边看边改」这个环节，也没有「中断」这个动作</b>
 * （见 {@link AiScene#SUMMARY} 的注释）。
 *
 * <p>★ 给它套 SSE 的代价不是「多写几行」：一旦有连接，就多出
 * 「流断了但摘要其实是好的」「用户中断了，那半截要不要存」这两类半成品状态 ——
 * 而定稿这次<b>已经把它标成「有 URL 但不是流式」的唯一场景</b>。
 *
 * <p>★★ <b>诚实记账：这条取舍有一个没解决的代价。</b>
 * 阻塞整整一次模型调用意味着：真实模型下这个请求可能要几秒到几十秒，
 * 而 {@code author-web/src/utils/request.js} 的超时是 <b>15 秒</b>（锁定文件，不能改）。
 * 也就是说接上真实模型之后，长区间的一次压缩<b>会在前端侧超时</b> ——
 * 后端仍然跑完并把摘要落库（模型的钱已经花了），但作者看到的是一个失败提示。
 * ★ E1 <b>不</b>把它异步化（那要任务表 + 轮询 + 进度，是另一期的量），
 * 但也<b>不</b>假装它已经解决：现在没有 UI 触发它（见 S12 的记录），
 * 真要给作者用之前，这条必须先处理。
 *
 * <h3>★★ 它【不做】重压 —— 而这不是偷懒，是那条链只有一个方向</h3>
 *
 * <p>{@code endChapterNo} 必须严格大于上一条摘要的 {@code end_chapter_no}，
 * 否则 400。理由见 {@link AiSummaryGenerateDTO#getEndChapterNo()}。
 * ★ 连带的已知限制：<b>某章在这次压缩时还是空的，它就永远进不了摘要链了</b>
 * —— 因为 {@code end_chapter_no} 已经越过它，而重压被上面那条挡住。
 * 这是刻意的：另一条路（允许重压）会让模型收到一段标签说假话的前情。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryService {

    /**
     * 单次压缩的正文长度上限（{@code String.length()} 计）。
     *
     * <p>★★ <b>它和 {@code AiRequestDTO.context} 的 {@code @Size(max = 8000)}
     * 是同一个数字，这是刻意的</b>：两者都是「一次送进模型的正文能有多长」。
     * 单位也必须一样 —— {@link PromptRenderer#MAX_RENDERED_LENGTH} 用的是
     * {@code String.length()}（UTF-16 code unit），不是字数、不是字节。
     * ★ 用错单位不会报错，只会让这道闸门在中文文本上偏保守或偏危险。
     *
     * <p>★★★ <b>超限时的处置是「明确失败」，绝不静默截断中段</b>：
     * 截断会让模型接着一段没头没尾的文字去写梗概，产出一段
     * <b>看起来正常、但中间缺了一段剧情</b>的摘要 —— 而它会被直接存库，
     * 从此每一章的前情都缺那一块，<b>而且没有任何地方提示过</b>。
     * ★ 这与 M5 铁律 10 是同一条：宁可 400，不产出「看起来正常的假结果」。
     *
     * <p>★ <b>阳性对照</b>：把本常量临时调大 → 同一个超长请求<b>真的会发出去</b>。
     * 这条必须真的跑一次，否则分不清「闸门拦住了」和「压根没走到闸门」。
     */
    static final int MAX_SOURCE_CHARS = 8000;

    private final NovelAccessGuard accessGuard;
    private final ChapterMapper chapterMapper;
    private final ChapterContentMapper chapterContentMapper;
    private final AiSummaryMapper summaryMapper;
    private final AiPreflight preflight;
    private final AiStreamSource streamSource;

    /**
     * 压缩一次，生成一条前情摘要并落库。
     *
     * @throws BusinessException 见下方注释里逐条的说明。★ 状态码由 {@link AiPreflight}
     *                           翻译成真实的 HTTP 状态码（本类抛的那几条是 400，
     *                           本来就落在真实段里，不需要翻译）
     */
    public AiSummaryVO generate(AiSummaryGenerateDTO req) {
        Long novelId = req.getNovelId();
        int requestedEnd = req.getEndChapterNo();

        /*
         * ---------- ① 归属校验：必须在读任何章节正文【之前】 ----------
         * ★ requireOwned 同时管「存在」与「是你的」两件事（见 NovelAccessGuard 的注释：
         *   它刻意把两者都返 200 + 各自的 code，那是防探测的设计）。
         * ★ 放在最前面不是为了快，是为了【顺序】—— 先读正文再判权限的话，
         *   一次越权请求在日志里会留下「读了别人的稿子」的痕迹。
         */
        accessGuard.requireOwned(novelId);

        /*
         * ---------- ② 接点：上一条摘要压到第几章 ----------
         * ★ 起点【由服务端算】，不接受客户端传（见 AiSummaryGenerateDTO 的注释）：
         *   摘要链是一条滚动往前接的链，让客户端指定起点就等于允许压一段中间会断的区间。
         */
        AiSummary previous = summaryMapper.selectLatestByNovelId(novelId);
        int startChapterNo = previous == null ? 1 : previous.getEndChapterNo() + 1;

        /*
         * ---------- ③ 区间必须往前 ----------
         * ★ 这一条挡的是「重压」，理由不是「不许重做」而是「那条链只有一个方向」：
         *   AiContextAssembler 能读到的「已有前情摘要」按定义就是 end_chapter_no
         *   最大的那一条 —— 也就是你要重压的这一段。于是模型收到的标签
         *   （模板里写的是「已有前情摘要（更早的剧情）」）在说假话，
         *   而它产出的摘要会被当成新的前情存下来。★ 宁可 400，不写一条自欺的链。
         */
        if (previous != null && requestedEnd <= previous.getEndChapterNo()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "这段剧情已经压缩过了：现有的摘要把故事讲到了第 " + previous.getEndChapterNo()
                            + " 章。请把 endChapterNo 传成比它更大的章节号（摘要只能一段段往前接）。");
        }

        /*
         * ---------- ④ 取区间内的章节 ----------
         * ★ 用 between(start, requestedEnd) 而不是「取最后 N 章」：范围是作者给的意图，
         *   我们不猜。★ 库里不存在的章节号自然不会被取到，也不报错 ——
         *   传一个比实际章节数大的 endChapterNo 不会失败，只是回报的区间小于请求的区间
         *   （见 AiSummaryVO 里那几个「实际覆盖」字段）。
         * ★ Chapter 有 @TableLogic，lambdaQuery 会自动带上 deleted = 0。
         */
        List<Chapter> chapters = chapterMapper.selectList(Wrappers.<Chapter>lambdaQuery()
                .eq(Chapter::getNovelId, novelId)
                .between(Chapter::getChapterNo, startChapterNo, requestedEnd)
                .orderByAsc(Chapter::getChapterNo));

        if (chapters.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "第 " + startChapterNo + "~" + requestedEnd + " 章不存在，没有可压缩的正文。");
        }

        /*
         * ---------- ⑤ 取正文 ----------
         * ★ 一次 selectByIds 而不是 N 次 selectById：区间可能有几十章。
         *   这是本项目里唯一的批量读法（同 AiAuditQueueService 的取章法）。
         */
        Map<Long, String> contents = loadContents(chapters);

        /*
         * ---------- ⑥ 拼正文 + 记录「实际覆盖到哪」 ----------
         * ★★ 拼进去的每一段带一个「第 N 章 标题」的表头。两个理由：
         *   ① 模型据此知道章节边界，产出的梗概才分得清「第 11 章发生了什么」；
         *   ② 摘要里提到「第 N 章」时和存储时的章节号是同一套编号 ——
         *      不然作者回头对不上号。
         */
        StringBuilder source = new StringBuilder();
        List<Integer> skipped = new ArrayList<>();
        int coveredStart = -1;
        int coveredEnd = -1;
        int coveredCount = 0;

        for (Chapter chapter : chapters) {
            String text = contents.get(chapter.getId());
            if (text == null || text.isBlank()) {
                /*
                 * ★★ 空章节【跳过并且报出来】（铁律：被跳过的条目必须报出来，绝不静默过滤）。
                 *   这里不抛异常：区间里有一章还没写是常态（作者刚建了章还没动笔），
                 *   为它拒掉整次压缩反而挡路。★ 但「实际覆盖区间」会因此小于请求区间 ——
                 *   那件事必须让作者看见，见 AiSummaryVO 的类注释。
                 */
                skipped.add(chapter.getChapterNo());
                continue;
            }
            if (coveredStart < 0) {
                coveredStart = chapter.getChapterNo();
            }
            coveredEnd = chapter.getChapterNo();
            coveredCount++;
            source.append("第").append(chapter.getChapterNo()).append("章");
            if (chapter.getTitle() != null && !chapter.getTitle().isBlank()) {
                source.append(' ').append(chapter.getTitle().trim());
            }
            source.append('\n').append(text.trim()).append("\n\n");
        }

        if (coveredStart < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "第 " + startChapterNo + "~" + requestedEnd + " 章都还没有正文，没有可压缩的内容。");
        }

        /*
         * ---------- ⑦ 长度护栏（★ 必须在闸门【之前】，见下） ----------
         * ★★ 放在 preflight 之前是刻意的：AiPreflight 的第 ⑤ 步会扣配额，
         *   而「作者把范围传大了」这件事【根本没有调用模型】——
         *   为一次没有发生的调用扣掉一次额度，正是 P5 起就在防的
         *   「失败不能有隐性代价」。
         * ★ 而且它必须先于组装：组装会去查 {novelTitle}/{summary} 并渲染，
         *   对一段注定要被拒的正文做那些事纯属浪费。
         */
        if (source.length() > MAX_SOURCE_CHARS) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "本次要压缩的正文共 " + source.length() + " 字（第 " + coveredStart + "~" + coveredEnd
                            + " 章），超过单次上限 " + MAX_SOURCE_CHARS
                            + " 字。请把 endChapterNo 调小一些分几次压缩 —— 摘要是往前接的，"
                            + "压完一次再接着压下一段即可。");
        }

        /*
         * ⑧ 组装请求。★ prompt 不设值是有意的，见下面那段注释。
         */
        AiRequestDTO dto = new AiRequestDTO();
        dto.setNovelId(novelId);
        dto.setContext(source.toString());
        /*
         * ★★★ 这里【不设 prompt】，而 AiVarResolver 把 SUMMARY 的 {{request}}
         *   声明成 NOT_APPLICABLE —— 两处是同一件事的两面。
         *   ★ 如果为了「字段填满」在这里编一句「请把下面这段压缩成摘要」，
         *     那句话【模板里已经有】了（种子模板的 user_template 结尾就是这句）。
         *     模型会收到同一句指令说两遍，更糟的是它会成为 {{request}} 的值 ——
         *     而模板里根本没有那个占位符，于是这句编出来的话【永远不会被渲染】，
         *     变成一个「写了但到不了模型」的死字段：编译通过、日志里也看不出问题。
         *   ★ 而 REQUIRED 声明更不行：那会逼着服务端编一句话来满足校验。
         */

        /*
         * ---------- ⑨ 前置闸门（鉴权 / 停用 / 模板 / 组装+渲染 / 配额） ----------
         * ★ 复用 SSE 那条闸门，一行都不另写。它在请求线程上跑、失败时抛真实状态码，
         *   这两点对一个 JSON 端点同样是我们要的 —— 见下面「为什么返回真实状态码是对的」。
         * ★★ 它内部会再调一次 assembler.assemble，而组装器为了解 {{summary}} 会
         *   再查一次「最新摘要」和 requireOwned —— 也就是说本方法的前两步在这里
         *   各被重复了一次。这是【刻意】的：那条规则（谁能读哪个作品的什么）
         *   只能有一个归属地，为了省两次主键级查询把它抄一份到这里，才是真的错。
         */
        RenderedPromptVO rendered = preflight.check(AiScene.SUMMARY, dto);
        dto.setRenderedSystemPrompt(rendered.getSystemPrompt());
        dto.setRenderedUserMessage(rendered.getUserMessage());

        /*
         * ---------- ⑩ 生成（★ 同步、就在本线程上） ----------
         * ★ AiStreamSource 的注释说「调用方必须把它放到独立线程上跑」——
         *   那条要求是为 SSE 服务的：Controller 必须【返回】emitter，才能让 Spring
         *   接管响应、才有流式可言。本方法没有 emitter 要返回，
         *   「生成期间占住线程」正是同步语义本身。见类注释那段取舍。
         * ★ 因此 Handler 的第二个契约（onChunk 允许被随时打断）在这里退化成空操作：
         *   没有连接可断，onChunk 永不抛 —— 收下每一个 chunk 即可。
         */
        Collector collector = new Collector();
        streamSource.stream(AiScene.SUMMARY, dto, collector);

        /*
         * ---------- ⑪ 产出必须合格才落库 ----------
         * ★★★ 与队列页那条「没审成 = 没有这一行」同构：摘要是【直接落库、不经采纳】
         *   的，所以「生成失败」必须表达为「没有新行」，绝不能让一行半成品进库 ——
         *   它从下一章起就是模型眼里的既成事实。
         */
        if (collector.failure != null) {
            log.error("前情摘要生成失败 | novelId={} | user={} | scene={}",
                    novelId, UserContext.getUserId(), AiScene.SUMMARY.getCode(), collector.failure);
            throw new BusinessException(ResultCode.AI_CALL_FAILED);
        }
        if (!collector.completed) {
            /*
             * ★★ 「没有终结回调」是一种必须单独拦下的形状：它意味着流被截断了
             *   （连接断掉、上游没发 [DONE]）。这时候 text 里可能是【一段通顺但没写完】
             *   的梗概 —— 存进去不会报任何错，而它讲的是一段不完整的故事。
             *   ★ SSE 那条链上，同样的形状由前端「流结束但没有 [DONE]」兜住；
             *   这里是落库的，兜底只能在这里。
             */
            log.error("前情摘要的流没有正常结束（可能被截断）| novelId={} | user={} | 已收到={} 字",
                    novelId, UserContext.getUserId(), collector.text.length());
            throw new BusinessException(ResultCode.AI_CALL_FAILED, "AI 的响应没有正常结束，本次未写入摘要，请重试。");
        }
        String summary = collector.text.toString().trim();
        if (summary.isEmpty()) {
            /*
             * ★ 空产出也要【明确失败】。理由不只是「存空串没用」：
             *   空摘要一旦落库，下一章的 {{summary}} 就【有值了】（只是值为空），
             *   于是那一段「已有前情摘要（更早的剧情）：」会带着一片空白发给模型 ——
             *   正是判断 2.2 里「可选变量无值必须整行移除」要避免的那种残缺提示词。
             *   ★ 文案里说清「额度已消耗」：配额不退还（模型真的被调用了），
             *     不写这一句的话，作者会以为重试不花钱。
             */
            log.warn("前情摘要产出为空 | novelId={} | user={}", novelId, UserContext.getUserId());
            throw new BusinessException(ResultCode.AI_CALL_FAILED,
                    "AI 没有返回任何内容，本次未写入摘要（本次调用已计入额度），请重试。");
        }

        /*
         * ---------- ⑫ 落库 ----------
         * ★★ 存的是【实际覆盖】的区间（coveredStart ~ coveredEnd），不是请求的区间：
         *   链条的接续靠的就是这一列 —— 存成请求区间的话，下一次会从「没压到的那些章」
         *   之后开始，而中间那几章永远不会被任何一条摘要覆盖。
         * ★ uk_novel_end (novel_id, end_chapter_no) 保证一对一：同一本书同一个截止章节
         *   不可能有两条摘要。真撞上了（并发两次同区间压缩）这里会抛
         *   DuplicateKeyException → 500，那是对的：**第二条摘要没有被写进去**，
         *   比「写进去了但两条讲同一段」好。
         */
        AiSummary row = new AiSummary();
        row.setNovelId(novelId);
        row.setStartChapterNo(coveredStart);
        row.setEndChapterNo(coveredEnd);
        row.setSummary(summary);
        summaryMapper.insert(row);

        log.info("前情摘要已生成 | novelId={} | user={} | 覆盖=第{}~{}章 | 跳过={} | 正文={} 字 | 摘要={} 字 | id={}",
                novelId, UserContext.getUserId(), coveredStart, coveredEnd, skipped,
                source.length(), summary.length(), row.getId());

        return toVO(row, coveredCount, skipped, source.length());
    }

    /* ==================== 私有 ==================== */

    private Map<Long, String> loadContents(Collection<Chapter> chapters) {
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        Map<Long, String> contents = new HashMap<>(chapterIds.size());
        for (ChapterContent content : chapterContentMapper.selectByIds(chapterIds)) {
            contents.put(content.getChapterId(), content.getContent());
        }
        return contents;
    }

    private AiSummaryVO toVO(AiSummary row, int coveredCount, List<Integer> skipped, int sourceChars) {
        AiSummaryVO vo = new AiSummaryVO();
        vo.setId(row.getId());
        vo.setNovelId(row.getNovelId());
        vo.setStartChapterNo(row.getStartChapterNo());
        vo.setEndChapterNo(row.getEndChapterNo());
        vo.setSummary(row.getSummary());
        /*
         * ★★ 章数【数出来的】，不是「区间长度减跳过的」算出来的 —— 后者是错的，
         *   而且错得很隐蔽：它把 {start} 已经躲开的那些空章又减了一遍。
         *   实测症状：区间 [5,6]、跳过了第 4 章，算出来是 6-5+1-1 = 1 章，
         *   而真正参与压缩的是 2 章。（★ 这条是 S10 验收的 E4 抓出来的。）
         */
        vo.setChapterCount(coveredCount);
        vo.setSourceChars(sourceChars);
        vo.setSkippedChapterNos(skipped);
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }

    /**
     * 把流式回调收敛成一个字符串。
     *
     * <p>★ 三个回调各自只做一件最小的事，判断全在 {@link #generate} 里 ——
     * 这样「什么算失败」只有一处，而这个类是唯一需要知道它的地方。
     */
    private static final class Collector implements AiStreamSource.Handler {

        private final StringBuilder text = new StringBuilder();
        private Throwable failure;
        private boolean completed;

        @Override
        public void onChunk(String chunk) {
            text.append(chunk);
        }

        @Override
        public void onComplete() {
            this.completed = true;
        }

        @Override
        public void onError(Throwable cause) {
            this.failure = cause;
        }
    }
}
