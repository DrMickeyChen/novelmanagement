package com.novel.common.result;

import java.util.List;

/**
 * {@link PageResult} 的装配工厂 —— <b>★★ 本类不引用任何 ORM 类型，这是它的全部意义。</b>
 *
 * <p>凡是要构造分页体、但<b>手上没有 MyBatis-Plus 的 {@code IPage}</b> 的地方，都从这里走。
 * M4 起 {@code novel-search} 是第一个这样的地方：它的 {@code total} 来自 ES 的
 * {@code hits.total.value}，页内容来自 content 的装配端点，<b>全程没有 {@code IPage} 这个东西</b>。
 *
 * <p>★★ <b>「{@code IPage} 的适配器」住在隔壁的 {@link MybatisPageResults}，两边【必须】分开。</b>
 * 理由不是分层洁癖，是一次实测故障：JVM 的 {@code Class.getDeclaredMethods()}
 * 会解析<b>每个方法签名</b>里的类型，所以只要本类里出现过一个 {@code IPage} 参数，
 * 一个没有 MyBatis-Plus 的服务<b>反序列化 {@code PageResult} 时就会 {@code NoClassDefFoundError}</b>；
 * 而且<b>编译期就会先报一次</b>「无法访问 {@code IPage}」（重载决议要解析所有候选）。
 * ★ 完整的三个层次与症状对照写在 {@link MybatisPageResults} 的类注释里。
 *
 * <p>★ 命名照 {@code NovelVos} 的先例：<b>「某个 DTO 的静态工厂」自成一类</b>，
 * 好处是它的依赖可以比 DTO 本身更重 —— 让 DTO 保持成一根光溜溜的、谁都能反序列化的数据管子。
 * ★ 这里更强：本类和 {@code NovelVos} 不一样，它是<b>刻意做到零依赖</b>的。
 *
 * @author MickeyChen
 */
public final class PageResults {

    private PageResults() {
    }

    /**
     * 从「页内容 + 总数 + 页码 + 页大小」拼一个分页体，<b>总页数由本方法算</b>。
     *
     * <p>★ 算法照 MyBatis-Plus 的 {@code Page#getPages()}：<b>向上取整，且 {@code total = 0} 时是 0 页</b>
     * 而不是 1 页（前端 {@code el-pagination} 在 0 条时靠它把分页器藏起来）。
     *
     * <p>★★ <b>入参请传【夹过之后】的 page / size</b>，不要传原始的请求参数。
     * 照 {@code PageResult} 的既有口径：那边是 MyBatis-Plus 的 {@code Page} 在汇报，
     * 而 {@code Page} 里存的就是分页插件夹过之后的值 —— 两条路要一致，
     * 否则 {@code ?page=0&size=0} 时「ES 路径写着 current=0、降级路径写着 current=1」。
     *
     * @param records 当前页数据，可为 {@code null}（按空列表处理）
     * @param total   总条数
     * @param current 当前页码，从 1 开始
     * @param size    每页条数，&lt;= 0 时总页数按 0 算
     */
    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        long pages = size <= 0 ? 0 : (total + size - 1) / size;
        return new PageResult<>(
                records == null ? List.of() : records,
                total,
                current,
                size,
                pages
        );
    }
}
