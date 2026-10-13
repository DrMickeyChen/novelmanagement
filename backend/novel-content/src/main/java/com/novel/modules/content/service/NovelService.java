package com.novel.modules.content.service;

import com.novel.common.enums.NovelStatus;
import com.novel.common.internal.NovelCollectCountDTO;
import com.novel.common.internal.NovelIndexDoc;
import com.novel.common.result.PageResult;
import com.novel.common.vo.NovelBriefVO;
import com.novel.common.vo.ChapterBriefVO;
import com.novel.common.vo.NovelVO;
import com.novel.modules.content.dto.NovelCreateDTO;
import com.novel.modules.content.dto.NovelUpdateDTO;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.vo.ChapterVO;
import com.novel.modules.content.vo.NovelDetailVO;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 作品服务。读者端 / 作者端 / 管理端三处共用。
 *
 * <p><b>★★ 这个接口里没有、也永远不该有 {@code setStatus} 之类的方法。</b>
 * 作品状态的<b>唯一</b>改动入口是 {@code NovelServiceImpl#transitStatus}，
 * 它是 {@code private} 的，对外只暴露一个个<b>业务意图</b>：
 * {@link #submit}（作者提交）、{@code audit}（管理员通过/驳回）、
 * {@code changeStatus}（管理员上下架）。
 * <p>这样设计的直接后果是：<b>Controller 拿不到一个「把状态设成 X」的方法</b>，
 * 于是「绕开状态机改状态」这件事在类型层面就做不到了 ——
 * 而绕开状态机就意味着绕开了审核日志。见大纲 §1345。
 *
 * <p><b>★ 三个端的口径差异都落在方法名上，不靠参数区分：</b>
 * {@code readerXxx} 只返回已上架的，{@code authorXxx} / {@code myXxx} 不过滤状态，
 * {@code adminXxx} 不过滤状态。用方法名区分而不是加一个
 * {@code boolean forReader} 参数 —— 参数传错的后果是<b>内容泄露</b>，
 * 而方法名是写在调用处的，review 时一眼能看见。
 *
 * @author MickeyChen
 */
public interface NovelService {

    /** Administrator-only publication of a fully written external import. */
    void publishImported(Long novelId);

    /* ==================== 读者端（匿名可访问） ==================== */

    /**
     * 读者端作品列表。
     *
     * <p><b>★ 只返回已上架的</b>（{@code status = PUBLISHED}），
     * 而且这个条件写在 {@code WHERE} 里，不是查出来再筛 ——
     * 后者在列表接口上<b>必然漏</b>：混进一本未上架的书不会报错，只是悄悄多一行。
     *
     * @param categoryId 分类筛选，{@code null} 不限
     * @param keyword    书名关键词，{@code null} 不限
     * @param isFinish   0 连载 / 1 完结，{@code null} 不限
     * @param sort       排序：{@code latest}（默认，最后更新）/ {@code new}（最新创建）/
     *                   {@code words}（字数最多）。<b>不认识的值一律按 {@code latest} 处理</b>，
     *                   不报错。★ 排序字段走白名单映射，绝不把入参拼进 SQL
     * @param page       页码，从 1 开始
     * @param size       每页条数
     */
    PageResult<NovelVO> readerPage(Long categoryId, String keyword, Integer isFinish,
                                   String sort, long page, long size);

    /**
     * 读者端作品详情。<b>P6 起不再含目录</b> —— 目录走
     * {@link #chapterPage}（分页，详情页用）和 {@link #catalog}（全量精简，阅读器用）。
     *
     * <p><b>★★ 刻意不区分「不存在」和「未上架」，两者一律 {@code NOVEL_NOT_FOUND(2001)}。</b>
     * 否则匿名调用者可以用错误码的差别枚举出「哪些 id 是真实存在的书」——
     * {@code /api/novels/{id}} 在白名单里，任何人不用登录就能扫。
     * <p>代价是作者看不到「作品尚未上架」这个更友好的提示，
     * 但那本来就该去<b>作者端</b>看（{@code GET /api/author/novels/{id}}），
     * 读者端不承担预览职责。
     */
    NovelDetailVO readerDetail(Long id);

    /**
     * 某本书的目录（分页），<b>只含已发布章节</b>，按 {@code chapterNo} 正序。
     *
     * <p><b>★ 为什么把目录从详情里拆出来：</b>详情页是匿名、高频的浏览入口，
     * 而这本书可能有几百章 —— 每打开一次详情就拖回全部章节，
     * 那些流量里绝大多数只是「看一眼简介」。
     *
     * <p><b>★ 不存在 / 未上架一律 {@code 2001}</b>，和 {@link #readerDetail} 同码：
     * 这两个接口在前端是挨着调的，一个 2001、另一个返回空列表的话，
     * 「这本书到底存不存在」就从一个错误码里漏出去了。
     *
     * <p>★ 返回的 {@code total} 就是「已发布章节数」，前端不要再另开一个接口要它。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，夹到 1~100（{@code buildPage}）
     */
    PageResult<ChapterVO> chapterPage(Long novelId, long page, long size);

    /**
     * 某本书的<b>全量精简目录</b>，给阅读器用 —— 上一章/下一章和目录抽屉
     * 都需要「整条有序列表」。
     *
     * <p><b>★★ 和 {@link #chapterPage} 不是重复的，不要合并：</b>
     * 详情页要的是「翻页浏览」，阅读器要的是「我在第几章、前后各是谁」。
     * 前者是分页的一份切片，后者必须完整 ——
     * 合并成一个的话，要么阅读器得翻 N 页才拼得出邻居，
     * 要么详情页得拖回整本目录。
     *
     * <p>★ 只含 {@code id} / {@code chapterNo} / {@code title}
     * （{@code ChapterBriefVO}），一条几十字节，几百章也就几十 KB。
     */
    List<ChapterBriefVO> catalog(Long novelId);

    /**
     * 某本书的<b>全量目录（含全部状态）</b>，给<b>管理端</b>审核用。
     *
     * <p><b>★ 为什么管理端要单独一个、而不是复用 {@link #chapterPage}：</b>
     * 那个只返回已发布章节。审核员要看的恰恰是<b>还没发布的那些</b> ——
     * 一本章节全是草稿的书，作品审核通过后读者进来只有一个空目录，
     * 「上架了等于没上架」，这个判断只能靠看到全部状态做出来。
     *
     * <p>★ <b>不分页</b>，行为与 P6 之前 {@code NovelDetailVO.chapters} 逐字一致
     * （那一份也是全量 + 全部状态）。管理端详情是低频的登录后操作，
     * 和读者端那个匿名高频入口不是一回事 —— 拆目录要解决的是后者。
     *
     * <p><b>★★ 用 {@code requireNovel}（只判存在），不做归属校验</b> ——
     * 管理员审的就是别人的书，加归属校验等于审核功能不存在。
     *
     * @throws com.novel.common.exception.BusinessException 作品不存在 {@code 2001}
     */
    List<ChapterVO> adminCatalog(Long novelId);

    /**
     * 读者端榜单。一次返回固定条数，<b>不分页</b>（榜单就是「前 N 名」，翻页没有意义）。
     *
     * <p><b>三个榜共用一个接口、用 {@code type} 区分</b>，理由：
     * 它们查的是同一张表、同一组可见性条件，只是排序不同；
     * 拆成 {@code /novels/hot}、{@code /novels/new}、{@code /novels/finished}
     * 三条路径会让「加一个榜」变成「加一个接口 + 一个前端 api 函数 + 一条白名单」。
     *
     * <p>★ <b>路径用的是 {@code /api/novels/rank}，不是大纲 §6.3 里写的
     * {@code /api/novels/hot}。</b>三条理由：
     * ① 三个榜是同一个接口，{@code /hot?type=new} 自相矛盾；
     * ② 大纲 §7.1 的页面本来就叫 {@code /rank}；
     * ③ 它彻底避开了 {@code /api/novels/{id}} 的路径变量竞争 —— 虽然 Spring
     * 的 {@code PathPattern} 比较器里字面量优先于变量（{@code /rank} 会稳定命中），
     * 但一旦有人把路径拼错成 {@code /api/novels/hott}，请求会掉进 {@code {id}} 分支，
     * 报的是「Failed to convert 'hott' to Long」这种完全指不到问题所在的错。
     *
     * <p>★ 三个榜都<b>只含已上架的作品</b>（同 {@link #readerPage}），
     * 并且都在末尾按 id 兜底排序，理由同
     * {@code NovelServiceImpl#applyReaderSort} 的注释。
     *
     * @param type  {@code hot}（阅读量降序，默认）/ {@code new}（创建时间降序）/
     *              {@code finished}（已完结，再按阅读量降序）。
     *              <b>不认识的值不报错，按 {@code hot} 处理</b> —— 和
     *              {@link #readerPage} 的 {@code sort} 参数一个约定，
     *              免得前端加个 Tab 就要后端配合发布
     * @param limit 返回条数。<b>会被夹到 {@code [1, 50]}</b>，{@code null} 视为 10。
     *              上限不是 100 而是 50：榜单是首屏组件，再多也没人往下看，
     *              而每一次都是全表排序，给一个匿名接口留 100 条的额度没有意义
     */
    List<NovelVO> rank(String type, Integer limit);

    /**
     * 全站搜索：<b>书名 OR 作者昵称</b>，命中任一即可。
     *
     * <p><b>★ 为什么单独开一个方法 / 一条路径，而不复用 {@link #readerPage} 的
     * {@code keyword} 参数</b>：语义不同。列表页的 {@code keyword} 是
     * 「<b>在当前分类里</b>筛书名」，搜索页是「<b>跨全站</b>搜书名或作者名」。
     * 混成一个方法会让分类页的筛选框在用户输入作者名时返回一堆不在该分类的书 ——
     * 而且看起来「挺合理」，不报错。
     *
     * <p><b>★★ 可见性条件必须用括号包住，这是这个方法唯一的实现难点。</b>
     * 如果平铺写成 {@code .eq(status, PUBLISHED).like(title, kw).or().in(authorId, ids)}，
     * 生成的是
     * <pre>WHERE status = 2 AND title LIKE ? OR author_id IN (...)</pre>
     * 而 SQL 里 {@code AND} 优先级高于 {@code OR}，它等价于
     * <pre>WHERE (status = 2 AND title LIKE ?) OR (author_id IN (...))</pre>
     * —— <b>只要作者命中了，那本书是不是已上架根本不参与判断，
     * 未上架的草稿会被搜出来。</b>这是真实的内容泄露，而且不报错、不崩溃，
     * 只是结果里多了几行。实现里用 {@code .and(w -> ...)} 显式加括号。
     *
     * @param keyword 关键词。<b>空白关键词直接返回空页，不进 SQL</b> ——
     *                否则会生成 {@code LIKE '%%'}（等于没过滤），
     *                而且作者 id 列表为空时还会生成 {@code IN ()} 语法错误
     */
    PageResult<NovelVO> search(String keyword, long page, long size);

    /**
     * 校验作品存在<b>且已上架</b>，返回实体；否则抛 {@code NOVEL_NOT_FOUND(2001)}。
     *
     * <p>给「读者端要写一点东西、但写的不是业务数据」的接口用 ——
     * 目前唯一的调用方是阅读量埋点 {@code POST /api/novels/{id}/view}。
     *
     * <p><b>★ 为什么埋点这种「丢了也无所谓」的数据还要校验存在性：</b>
     * 不是为了数据准确性，是为了<b>不给一个匿名接口提供无限造 key 的口子</b>。
     * 不校验的话，攻击者用随机 id POST 一百万个请求，就在 Redis 里留下
     * 一百万个永不过期的 {@code novel:novel:views:*} key —— 内存被吃满，
     * 而脏集合里的一百万个成员会让定时任务每轮空转。
     *
     * <p>★ <b>未上架的作品也不计数</b>：它的正文读者根本读不到
     * （详情接口和章节正文接口都会返回 2001），能点到这个接口的只有
     * 直接构造请求的人。计数它等于给未上架的内容留下可观测的痕迹。
     *
     * <p><b>★★ 和 {@link #readerDetail} 一样，刻意不区分「不存在」和「未上架」。</b>
     * 这个接口是免登录的（见 {@code SecurityConfig} 里那条 POST 白名单），
     * 一旦两种情况的错误码不同，它就变成了一把「哪些 id 是真实存在的书」的枚举探针 ——
     * 而且比详情接口更隐蔽，因为它返回的是空 body，容易被当成「埋点本来就没返回」。
     *
     * @throws com.novel.common.exception.BusinessException 不存在或未上架，均为 {@code 2001}
     */
    Novel requirePublished(Long id);

    /* ==================== 作者端 ==================== */

    /**
     * 我的作品列表。
     *
     * <p>★ 只返回<b>当前登录用户</b>的作品（{@code author_id = 当前用户}），
     * 而且这个条件是查询的一部分，不是查完再过滤。
     *
     * @param status 状态筛选，{@code null} 表示全部（含草稿 —— 作者要能看到自己的草稿）
     */
    PageResult<NovelVO> myPage(NovelStatus status, long page, long size);

    /**
     * 新建作品，存为草稿（{@code status = DRAFT}）。
     *
     * <p><b>★ 作者从入参里带不进来作者 id 和状态</b>：
     * {@link NovelCreateDTO} 上压根没有这两个字段（mass-assignment 防御）。
     * 作者 id 取当前登录用户，状态固定为草稿。
     * <p>否则作者可以在 POST body 里塞一个 {@code "status": 2}，
     * 一次请求就把书直接上架 —— <b>审核被整个绕过</b>。
     *
     * @return 新作品的 id
     */
    Long create(NovelCreateDTO dto);

    /**
     * 作者查看自己作品的详情（<b>含草稿</b>）。
     *
     * @throws com.novel.common.exception.BusinessException 不存在 {@code 2001}；
     *         不是自己的 {@code 2002}
     */
    NovelDetailVO authorDetail(Long id);

    /**
     * 编辑作品信息（书名 / 封面 / 分类 / 简介 / 标签 / 是否完结）。
     *
     * <p>★ 状态不允许编辑时必须拒绝（{@code NovelStatus#isEditable}），
     * 尤其<b>待审核期间不能改</b> —— 审核员看的是 A 版简介、作者偷偷换成 B 版，
     * 审核员点通过，上架的是 B，而日志上看起来是一次完全正常的审核。
     */
    void update(Long id, NovelUpdateDTO dto);

    /**
     * 删除作品。
     *
     * <p><b>★ 作品下有章节时拒绝删除</b>（{@code NOVEL_HAS_CHAPTERS(2005)}）。
     * 不拒绝的话，{@code chapter} 表里会留下一堆 {@code novel_id} 指向
     * 一本查不到的书，而它们本身还是「已发布」状态 ——
     * 读者端的目录按 novel_id 查，查不到书所以进不来，
     * 但管理端的章节列表会照常列出这些孤儿章节，
     * 审核员完全不知道它们属于哪本书（{@code novelTitle} 是空的）。
     * <p>正确做法是先删章节，或把作品改成「下架」。
     */
    void delete(Long id);

    /**
     * 提交审核（{@code DRAFT/REJECTED/OFFLINE → PENDING}）。
     *
     * <p>★ 只能提交<b>自己的</b>作品。走到状态机之前会先过归属校验。
     */
    void submit(Long id);

    /**
     * 撤回提交（{@code PENDING → DRAFT}）—— <b>P4 补的</b>。
     *
     * <p><b>★ 它补的是一个明确记录在案的缺口。</b>
     * {@code NovelStatus#isEditable} 的注释原来写着：作者提交后发现简介有错别字，
     * 在审核出结果前改不了，只能等管理员驳回，「正经的解法是做撤回提交，
     * 但那需要一条 {@code PENDING → DRAFT} 的流转，属于 P4 创作中心的范围」。
     * 这就是那条流转。
     *
     * <p><b>★ 它不绕过审核，这一点必须说清楚：</b>
     * 撤回只是把作品<b>从审核队列里拿走</b>，让它回到作者手里。
     * 撤回之后状态是 {@code DRAFT}，改完还要重新提交、重新审。
     * 真正会绕过审核的是「让 {@code PENDING} 也能编辑」，那个仍然被拦着。
     * <p>而且撤回和「通过」是两条互斥的 UPDATE：审核员恰好同时点了通过时，
     * 谁先提交谁生效，后到的那个会读到已经变了的状态而失败 ——
     * 不会出现「改完的内容被上架了」。
     *
     * <p>★ 只有 {@code PENDING} 能撤回（由状态机限死），
     * 其余状态调用返回 {@code 2003}。已上架的书要改，走「下架」那条路。
     */
    void withdraw(Long id);

    /* ==================== 管理端 ==================== */

    /**
     * 管理端作品列表。<b>不过滤状态</b>（含草稿、待审核、已驳回）。
     *
     * <p>★ 和 {@link #readerPage} 的唯一区别就是「不加 {@code status = PUBLISHED} 这个条件」，
     * 但这一个差别决定了审核功能能不能用 —— 加了就看不到待审核的书。
     * 两个方法刻意都写全，不抽公共方法：抽出来必然要收一个
     * 「要不要过滤状态」的布尔参数，而那个参数传错的后果是内容泄露。
     */
    PageResult<NovelVO> adminPage(NovelStatus status, Long categoryId, String keyword,
                                  long page, long size);

    /**
     * 管理端作品详情（含全部章节，含驳回理由）。
     *
     * <p>★ <b>不做归属校验</b> —— 管理员审的就是别人的书。
     * 这里用「存在性校验」而不是「归属校验」，是它和 {@link #authorDetail} 的唯一区别。
     */
    NovelDetailVO adminDetail(Long id);

    /**
     * 审核：通过 → 已上架，驳回 → 已驳回。
     *
     * <p><b>★ 只接受「待审核」的作品</b>，其余状态一律 {@code 2003}。
     * 状态机本身允许 {@code OFFLINE → PUBLISHED}（那是「重新上架」），
     * 所以光靠状态机挡不住「对已下架的书点审核通过」——
     * 这个前置判断是必须的，不是冗余。见实现里的注释。
     *
     * @param pass   {@code true} 通过，{@code false} 驳回
     * @param remark 备注，驳回时是驳回理由（会给作者看到）
     */
    void audit(Long id, boolean pass, String remark);

    /**
     * 上架 / 下架。
     *
     * <p>★ {@code target} <b>只接受 2（上架）和 3（下架）</b>，传别的抛 {@code 400}。
     * DTO 上是枚举类型，所以 {@code {"status": 0}} 能通过反序列化 ——
     * 类型系统挡不住它，必须在业务层挡。
     *
     * <p>之所以单独开一个接口而不是让 {@link #audit} 兼职：
     * 「审核通过」和「重新上架」在状态机上是同一个流转（都到 PUBLISHED），
     * 但它们是两件事，日志里必须分开记（{@code APPROVE} vs {@code REPUBLISH}）。
     */
    void changeStatus(Long id, NovelStatus target, String remark);

    /* ==================== 供 ChapterService 调用 ==================== */

    /**
     * 重算作品的四个冗余字段（{@code chapter_count} / {@code word_count} /
     * {@code last_chapter_id} / {@code last_update_time}）。
     *
     * <p><b>★★ 用「重算」而不是「增量」，这是本方法存在的全部意义。</b>
     * 增量（{@code UPDATE novel SET chapter_count = chapter_count + 1}）要求
     * 「每一条增 / 删 / 通过 / 退回的路径都记得调、且方向正确」——
     * 而「删一章忘了减 1」是<b>必然会发生</b>的漂移，更糟的是没人会发现
     * （页面上显示 12 章、实际 13 章，谁去数）。重算是<b>一处实现、永远自洽</b>。
     *
     * <p><b>★ 调用方只能是 {@code ChapterService} 和 {@code NovelServiceImpl} 自己。</b>
     * Controller 不要调 —— 从 Controller 调它，说明那条路径绕开了状态机
     * （状态机内部已经会调了，多调一次结果一样，但暴露了调用者不知道状态机在哪）。
     *
     * <p>代价：每次调用多两条聚合查询。{@code chapter} 上的
     * {@code idx_novel_status(novel_id, status)} 正好覆盖条件，可以忽略。
     */
    void syncNovelStat(Long novelId);

    /* ==================== 供 interaction 模块调用（书架 / 书评） ==================== */

    /**
     * 批量取作品摘要，返回 {@code novelId → NovelBriefVO}。
     *
     * <p>给「手里只有一串 novelId」的列表用 —— 目前是书架的「我的书架」和
     * 阅读历史的列表（两者都在 {@code interaction} 模块，数据在它自己的表里）。
     *
     * <p><b>★★ 只返回【已上架】的作品，未上架 / 已删除的 id 在 map 里根本没有这个 key。</b>
     * 调用方拿 {@code get(novelId)} 得到 {@code null}，要自己决定怎么显示
     * （书架的取法是显示成「已下架」的占位卡，而不是把这一行去掉 ——
     * 读者加入书架的书被作者下架后从列表里凭空消失，看起来像书架丢了数据）。
     * <p>过滤放在这里而不是让调用方自己查 {@code status} 再判断：这是读者侧的
     * 可见性规则，<b>只应该有一处实现</b>。调用方各自判断，迟早有一处忘了判，
     * 于是下架作品的封面和书名从某个接口漏出去。
     *
     * <p><b>★ 与 {@link #readerPage} 的区别是不分页、只按 id 集合取</b>，
     * 所以它接的是 {@code Collection<Long>} 而不是 page/size。
     * 空集合直接返回空 map，<b>不进 SQL</b>（{@code IN ()} 是语法错误，不是空结果）。
     *
     * <p>★ 一次 {@code IN} 批量查完，分类名和作者昵称也各是一次批量查 ——
     * 调用方<b>不要</b>循环调 {@code readerDetail}，那是 N+1。
     */
    Map<Long, NovelBriefVO> briefsOf(Collection<Long> novelIds);

    /**
     * 把 {@code novel.collect_count} 刷成 {@code count}。<b>写的是绝对值，不是增量。</b>
     *
     * <p><b>★★ 为什么是「重算后写回」而不是「{@code collect_count + 1}」：</b>
     * 增量的正确性依赖「每一条加 / 减路径都记得调、且方向正确」，而
     * 「移出时忘了减」是<b>必然会发生</b>的漂移；更糟的是漂移出来的数字
     * <b>看起来永远合理</b> —— 页面上显示 87 人收藏，而实际 86 人，
     * 没有任何人会去数。重算是一处实现、永远自洽，代价只是一次
     * {@code COUNT(*)}，而 {@code user_shelf.idx_novel_id} 正是为它建的。
     * <p>同 {@link #syncNovelStat} 的「重算不增量」，那是作品的章节数冗余列。
     *
     * <p><b>★ 为什么由调用方算好再传进来，而不是在这里 COUNT：</b>
     * {@code user_shelf} 是 {@code interaction} 的表。
     * {@code content} 读 {@code interaction} 的表就是反向依赖 ——
     * 二期的 {@code M3} 拆完会变成「{@code content} 调 {@code interaction}」的
     * Feign 调用，而 {@code interaction} 已经在调 {@code content}，
     * 两条方向一开就是双向依赖，谁都拆不动。所以口径是死的：
     * <b>值在 interaction 算，行在 content 写，跨表 SQL 一行都不出现。</b>
     *
     * @param count 绝对收藏数。<b>调用方负责不为负</b>；这里不再兜底，
     *              因为「算出负数」说明调用方的聚合本身错了，静默夹到 0 会把那个错盖掉
     */
    void refreshCollectCount(Long novelId, long count);

    /**
     * 把 {@code novel.score} 刷成 {@code score}（书评评分的平均分）。
     *
     * <p>口径与 {@link #refreshCollectCount} 完全一致（重算不增减、值在
     * interaction 算、行在 content 写），不再重复论证。
     *
     * <p><b>★ 两个只在实现里兜住的细节</b>（都是「手写 SQL 时 {@code @TableLogic} 不生效」
     * 那个坑的同类）：平均分只统计 {@code status = 1} 且 {@code deleted = 0}
     * 且 {@code score IS NOT NULL} 的书评；并且结果要 {@code ROUND(..., 1)} ——
     * {@code novel.score} 是 {@code DECIMAL(3,1)}，不 ROUND 会被隐式截断
     * （前端拿到 4.3333333 再自己截，和库里存的就不是一个数了）。
     *
     * <p><b>★ 没有评分的书传 {@code BigDecimal.ZERO}，不要传 {@code null}：</b>
     * 该列是 {@code NOT NULL DEFAULT 0.0}，传 null 会走
     * {@code set(col, null)} → {@code score = NULL}，下一次 {@code AVG} 拼进去
     * 就是一行 NULL 参与运算。前端按「{@code score > 0} 才显示星星」区分
     * 「0 分」和「暂无评分」，语义是「0 就是还没人评」。
     */
    void refreshScore(Long novelId, BigDecimal score);

    /* ==================== 供 interaction 的对账任务调用（M3） ==================== */

    /**
     * 【对账专用】按 id 升序取「收藏数非 0 的作品」的一页，每行只有 id 和收藏数。
     *
     * <p><b>★★ 这不是一个业务查询，它是「把 content 这边的账本摊开给 interaction 看」。</b>
     * 调用方只有一个：{@code interaction} 的 {@code NovelStatReconcileJob}
     * （每 10 分钟一次的对账任务）。它拿这边报出来的值和自己的
     * {@code COUNT(*)} 真值比对，不一致的重推一条 {@code NOVEL_STAT} 消息。
     *
     * <p><b>★★ 为什么是「翻页取全集」而不是「给我这几个 id 的值」：</b>
     * 对账要解决的是「那条消息丢了，所以这边停在旧值」——
     * 而<b>丢消息的现场不会留下任何痕迹</b>，没有任何 id 列表能圈出它。
     * 唯一能发现漂移的办法是把两边的全量摊开比一遍。
     * 反过来说：如果对账只需要回答「这几个 id 对不对」，
     * 那它就已经知道该查谁了 —— 而那正是它不知道的事。
     *
     * <p><b>★★ 为什么只报非 0 的，以及调用方【必须】把「不在这一页里」当成 0：</b>
     * <pre>
     *   这边不报 novelId=7  →  两种可能，调用方不必分辨：
     *     ① 第 7 本书的 collect_count 本来就是 0  → 值就是 0，没分歧
     *     ② 第 7 本书不存在 / 已逻辑删除          → 值推过去也没用（★ 见下）
     *   两种情况的处置一样：拿 0 去比。
     * </pre>
     * ★ 第 ② 种的代价要如实记一笔：一本被逻辑删除、而书架上还留着读者的书，
     * 会<b>每一轮都对不上</b>（调用方真值 &gt; 0，这边报不出它）——
     * 于是每 10 分钟重推一次，而消费端的 {@code UPDATE} 因为 {@code @TableLogic}
     * 影响 0 行。<b>这是刻意不处理的</b>：要消掉它就得让这边把已删除的书也报出来，
     * 那等于把一个「统计口径」悄悄改成「含墓碑的行集」，
     * 而这条噪声本身有信息量（有人在删一本有人收藏的书）。论证在
     * {@code NovelStatReconcileJob} 那边。
     *
     * <p><b>★★ 游标分页（{@code afterId}）而不是 {@code page}/{@code size}：</b>
     * 这是一次<b>全表扫描</b>，而 offset 分页在扫描过程中一旦有行插入，
     * 后面的页会整体错位 —— 漏掉一行或者重复一行。
     * 对账漏掉一行的后果不是「少修一个」，是<b>那个漂移永远修不回来</b>
     * （它不在任何一页里），而日志上看不出任何异常。
     * 游标分页对插入免疫：它只认「id 比上一页最后一个大」。
     * <p>★ 顺带：游标分页不需要 {@code COUNT(*)}，调用方也就不必先问「总共有多少页」。
     *
     * <p><b>★ 分页的上下界都不在这里夹</b>，由 {@code buildPage} +
     * {@code PaginationInnerInterceptor.setMaxLimit(100)} 那两层管（同 {@code rank}）。
     * ★ <b>但调用方必须知道 100 这个上限</b>：请求 {@code size &gt; 100} 时
     * 拿回来的是被截断到 100 的一页，而「返回条数 &lt; 请求条数」正是调用方
     * 判断「扫到底了」的依据 —— 于是循环会<b>提前结束、静默漏掉后面的书</b>。
     * 所以 {@code NovelStatReconcileJob} 里的页大小写死成 100（不是「随便取个大点的」）。
     *
     * @param afterId 游标：只返回 id <b>严格大于</b>它的行。第一页传 {@code null}（或 0）
     * @param size    这一页最多几行。★ ≤ 100，理由见上；{@code null} / 小于 1 走默认值
     * @return 按 id 升序的一页，可能为空列表（扫到底了）
     */
    List<NovelCollectCountDTO> collectCountsAfter(Long afterId, Integer size);

    /* ==================== 供 search 建索引调用（M4） ==================== */

    /**
     * 批量取「该进索引的」作品数据，返回 {@code {novelId: NovelIndexDoc}}。
     *
     * <p><b>★ 两个调用方，一个端点：</b>
     * <ul>
     *   <li><b>增量重建</b>（{@code NOVEL_INDEX} 消息触发）—— 传 1 个 id；</li>
     *   <li><b>检索结果的装配</b> —— 传 ES 命中的那一页 id。</li>
     * </ul>
     * 两条路要的是同一份数据，所以是同一个方法。★ 用 {@code POST} 而不是
     * {@code GET}（理由同 {@link #briefsOf}：id 列表塞 query string 会撞 URL 长度限制，
     * 而那个限制在不同服务器上不一样，症状是「书多了就报 400」、本地还复现不了）。
     *
     * <h3>★★★ 「响应里没有这个 id」= <b>这本书不该在索引里，请删掉它</b></h3>
     *
     * <p>这是本方法<b>唯一的隐式约定，也是最容易写错的一条</b>。缺一个 id 有三种成因，
     * 而调用方<b>不需要、也无法</b>分辨它们：
     * <pre>
     *   ① 作品不存在              → 索引里那条是脏的，删
     *   ② 作品被逻辑删除          → 同上（@TableLogic 让它查不出来，★ 见下）
     *   ③ 作品从未进过索引        → 删一个不存在的文档，ES 静默成功
     * </pre>
     * ★★ <b>第 ② 种是这条约定存在的真正理由。</b>{@code @TableLogic} 是 MyBatis 往
     * WHERE 里注入 {@code deleted = 0}，<b>ES 完全不知道有这回事</b> ——
     * 没有这条约定，一本被删掉的书会<b>永远留在索引里能被搜到</b>，
     * 而它在 MySQL 那边已经不存在了。所以「查不到 ⇒ 删」不是容错，是<b>正确性本身</b>。
     *
     * <h3>★★ 刻意【不看 status】—— 草稿也要进索引</h3>
     *
     * <p>直觉会说「只索引已上架的」，但那样会漏掉一条真实的链路：
     * <b>草稿 →（上架）→ 已上架</b>时，如果草稿从来没进过索引，
     * 那上架那一刻就必须「插入」而不是「更新」，而索引侧无从知道该插还是该改。
     * ★ 让所有状态都进索引、<b>由 ES 侧的 {@code status} 过滤挡住不可见的那部分</b>
     * （判断 5），索引侧就只剩一种写操作：<b>upsert</b>。
     *
     * <p>★★ 这也正是「第二道闸门」的物理位置：即使索引里躺着一条陈旧的已下架文档，
     * 用户在 ES 查询里就搜不到它。<b>索引陈旧的方向于是从「内容泄露」降级成
     * 「搜不全」</b>——后者可接受、且能被定时重建自愈。
     *
     * <p>★ 返回值是 {@code Map} 而不是 {@code List}，两个理由：
     * ① 「缺失」在 Map 里是显式的（{@code get} 返回 {@code null}），
     * 而在 List 里要靠调用方自己发现少了一个 —— 那是<b>不会报错的漏删</b>；
     * ② MySQL 的 {@code IN} <b>不保证返回顺序</b>，用 List 就得再约定一条
     * 「按传入 id 顺序重排」，多一条没人会记得的契约。★ 同 {@link #briefsOf} 的形状。
     *
     * @param novelIds 作品 id 集合。<b>空集合是合法输入</b>，返回空 Map，调用方不必特判
     * @return 查得到的那些 id 的索引数据；★ <b>存活的 id 一个都不会少</b>，
     * 因为调用方拿「少了」当删除信号 —— 分页/截断在这里是不可接受的（见下）
     */
    Map<Long, NovelIndexDoc> indexDocsOf(Collection<Long> novelIds);

    /**
     * 【全量重建专用】按 id 升序取<b>所有作品</b>的一页，每行是 6 个索引字段。
     *
     * <p><b>★★ 这是「定时全量重建」的数据源</b>，也是 M4 一切最终一致性的收敛点。
     * 上面 {@link #indexDocsOf} 那三条会漏的触发点（改名 / 下架 / 逻辑删除）
     * 全靠它在一个周期内抹平。
     *
     * <p>★★ <b>为什么必须是「翻全集」而不是「给我变过的那些」：</b>
     * 同 {@link #collectCountsAfter} —— <b>漏发的现场不留痕迹</b>，
     * 没有任何 id 列表能圈出「哪些书的消息丢了」。唯一能发现的办法是整体重算一遍。
     *
     * <p>★★ <b>游标分页（{@code afterId}）而不是 offset</b>，理由同
     * {@link #collectCountsAfter}：这是一次全表扫描，offset 在扫描中遇到插入会整体错位。
     * 全量重建漏掉一行的后果是那本书<b>从索引里消失</b>（重建是先删索引再灌），
     * 也就是<b>搜不到它</b>，而日志上看不出任何异常。
     *
     * <p>★ <b>已逻辑删除的作品不会出现</b>（{@code @TableLogic} 自动带 {@code deleted = 0}）——
     * 这正是我们要的：全量重建清了旧索引，墓碑不会跟着回来。
     *
     * <p><b>★ 分页上下界都不在这里夹</b>，由 {@code buildPage} +
     * {@code PaginationInnerInterceptor.setMaxLimit(100)} 两层管。
     * ★ <b>调用方必须知道 100 这个上限</b>，理由同 {@link #collectCountsAfter}：
     * 「返回条数 &lt; 请求条数」是调用方判断「扫到底了」的依据，
     * 请求 {@code size > 100} 会被静默截断到 100 → 循环<b>提前结束、漏掉后面的书</b>。
     *
     * @param afterId 游标：只返回 id <b>严格大于</b>它的行。第一页传 {@code null}（或 0）
     * @param size    这一页最多几行。★ ≤ 100；{@code null} / 小于 1 走默认值
     * @return 按 id 升序的一页，可能为空列表（扫到底了）
     */
    List<NovelIndexDoc> indexPageAfter(Long afterId, Integer size);

    /**
     * 【检索装配专用】给一组 id，返回<b>读者看得到的那些</b>作品，<b>按传入顺序</b>。
     *
     * <p><b>★★★ 这个方法和 {@link #indexDocsOf} 长得像，但【不能合并】——
     * 它们的 status 口径是相反的，合并就等于拆掉第二道闸门。</b>
     * <pre>
     *   indexDocsOf   →  ★ 不看 status（草稿也要进索引）
     *   visibleVosOf  →  ★★ 只看 status = 已上架
     * </pre>
     * ★ 判据是「谁在用」：前者喂给<b>倒排索引</b>，索引里必须什么都有一份，
     * 可见性由 ES 侧的 filter 挡；后者喂给<b>用户的搜索结果页</b>，
     * 它就是那道挡在前面的闸门本体。
     *
     * <h3>★★ 第二道闸门 —— 这是安全性的，不是性能的</h3>
     *
     * <p>这条规矩的出处是 {@code ReaderSearchController}（★ M4 已随搜索一起搬去
     * {@code novel-search}，类名在这里留着是为了让人还能顺藤摸到那段论证）：
     * <b>读者端的每一条查询都必须自己带可见性条件</b> —— 它免登录、没有登录态兜底，
     * 条件漏了就是<b>直接对外的内容泄露</b>。
     * <p>M4 把搜索搬到 ES 之后，「ES 里的文档陈旧了（书已下架但文档还在）」
     * 和「用户搜到了不该搜的」之间，<b>必须仍然隔着一道闸门</b> ——
     * 而 content 这边天然知道 {@code status}，search 连它是什么语义都不需要知道。
     * <p>★★ 于是索引陈旧的方向从「<b>内容泄露</b>」降级成「<b>搜不全</b>」：
     * 后者可接受、且能被定时全量重建自愈。
     *
     * <h3>★★ 为什么由 content 重排，而不是让 search 自己按 id 对</h3>
     *
     * <p>ES 返回的是<b>相关性排序</b>，那个顺序是这次检索的全部价值所在。
     * 而 MySQL 的 {@code IN} <b>不保证返回顺序</b>（它甚至可能随执行计划变化）——
     * 不重排的话，用户搜出来的结果顺序是随机的，
     * ★ 而它「看起来完全正常」，只是不相关。这类错误没有任何报错。
     * <p>★ 让 content 排（而不是 search 拿到 Map 自己按 ES 顺序取）的原因：
     * search 那边就只能用 Map，而 <b>Map 表达不了「重复 id 保留两份」</b>，
     * 也表达不了顺序 —— 一个需要调用方自己重组的返回类型，
     * 迟早会有人忘了重组。
     *
     * <p>★★ <b>返回条数可能少于请求条数</b>，这不是错误：被过滤掉的 id
     * 说明<b>索引里有 content 这边不可见的书</b>（下架了、删了、或者状态改过而消息丢了）。
     * ★ 那正是「索引陈旧」的唯一信号，所以本方法<b>会记 WARN</b>（见实现）。
     * ★ 代价如实记一笔：ES 给了 10 个 id、这边只回 8 个，
     * 于是<b>这一页只有 8 条</b>。正常运行时（两道闸门都在）一条都不会被过滤掉，页永远是满的。
     *
     * @param novelIds ES 命中的 id，<b>按相关性排好序</b>。空集合 = 空列表
     * @return 其中读者可见的那些，<b>顺序与传入一致</b>；可能为空列表
     */
    List<NovelVO> visibleVosOf(Collection<Long> novelIds);
}
