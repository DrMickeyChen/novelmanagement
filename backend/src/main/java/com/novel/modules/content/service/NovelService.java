package com.novel.modules.content.service;

import com.novel.common.enums.NovelStatus;
import com.novel.common.result.PageResult;
import com.novel.modules.content.dto.NovelCreateDTO;
import com.novel.modules.content.dto.NovelUpdateDTO;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.vo.NovelDetailVO;
import com.novel.modules.content.vo.NovelVO;

import java.util.List;

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
     * 读者端作品详情（含目录，<b>目录只含已发布章节</b>）。
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
}
