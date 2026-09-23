package com.novel.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.function.Function;

/**
 * {@code IPage} → {@link PageResult} 的适配器。
 * <b>★★ 它必须和 {@link PageResults} 分成两个类 —— 这是 M4 实测出来的，不是洁癖。</b>
 *
 * <h3>★★★ 一条 {@code NoClassDefFoundError} 换来的教训</h3>
 *
 * <p>M4 拆出 {@code novel-search}，它的 pom <b>刻意不加 MyBatis-Plus</b>
 * （没有 DataSource、没有 Mapper、连 MySQL 驱动都没有 —— 这是判断 3「ES 当 id 检索器、不当数据源」的结构性保证）。
 * 结果降级路径（走 content 的 {@code /internal/search}）一跑就炸：
 *
 * <pre>
 *   java.lang.NoClassDefFoundError: com/baomidou/mybatisplus/core/metadata/IPage
 *     at feign.ResponseHandler.handleResponse(...)
 * </pre>
 *
 * <h3>★ 三个层次，一个比一个不显眼 —— 都实测过</h3>
 *
 * <ol>
 *   <li><b>字段注入</b>第三方类型 → 起不来。一眼看得出来。</li>
 *   <li><b>{@code implements} 第三方接口</b> → <b>类加载阶段</b>就炸，比注入还早。
 *       （M4 判断 10 那张表里的 {@code MybatisMetaObjectHandler} 就是这一类。）</li>
 *   <li>★★★ <b>方法签名里【出现】第三方的类型</b> → 一样炸，而且它长得像
 *       「一个永远不会被调用的重载」。
 *       ★ 机制：{@code Class.getDeclaredMethods()} <b>必须解析每个方法签名里的类型</b>，
 *       解析不到就抛 {@code NoClassDefFoundError} —— <b>跟那个方法调没调用毫无关系。</b>
 *       于是 Jackson 一反序列化 {@code PageResult} 就炸。
 *       ★ 本类的两个方法原来就长在 {@code PageResult} 里，正是这一条。</li>
 * </ol>
 *
 * <p>★★ <b>而编译期比运行期更早发出同一条信号</b>：把 ORM 版和 ORM-free 版的重载<b>放进同一个类</b>
 * 也不行 —— javac 为了做重载决议要把候选方法的参数类型全都解析出来，
 * 于是 {@code novel-search} 一调那个 ORM-free 的重载就报
 * 「<b>无法访问 com.baomidou.mybatisplus.core.metadata.IPage</b>」。
 * <b>所以判据不是「别把 IPage 放在 DTO 里」，而是「IPage 不许和任何人共处一个类」。</b>
 * <b>症状对照：能编译、能启动、第一次降级才 500。</b>
 *
 * <p>★ 为什么不走「给 search 加上 mybatis-plus 依赖」那条更省的路：那会往一个<b>没有数据库</b>的服务里
 * 塞一个 ORM，从此谁都可以在 search 里注入 Mapper。★ 而且 {@link PageResult} 自己的类注释里就写着
 * 「{@code Page} 是 ORM 的类，不该跨服务传递」—— <b>这次是那个类自己违反了这句话</b>，
 * 把它改回来说得通的样子，比绕开它更对。
 *
 * <h3>★ 迁移代价：6 个真实调用点，纯机械</h3>
 *
 * <p>调用方从 {@code PageResult.of(page)} 改成 {@code MybatisPageResults.of(page)}，<b>参数和返回值一个字没变</b>。
 * ★ 这是 M4 唯一一次为了新服务回头改 {@code novel-common} 的<b>类型</b>
 * （另一处是给 {@code MybatisPlusConfig} / {@code MybatisMetaObjectHandler} 加条件注解），编译期可验。
 *
 * @author MickeyChen
 */
public final class MybatisPageResults {

    private MybatisPageResults() {
    }

    /**
     * 把 MyBatis-Plus 的 {@code IPage} 转成统一分页体。
     *
     * <pre>
     * Page&lt;Novel&gt; page = novelMapper.selectPage(new Page&lt;&gt;(current, size), wrapper);
     * return Result.success(MybatisPageResults.of(page));
     * </pre>
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(
                page.getRecords(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                page.getPages()
        );
    }

    /**
     * 转成统一分页体的同时，把实体转成 VO。
     *
     * <pre>
     * PageResult&lt;NovelVO&gt; vo = MybatisPageResults.of(page, this::toVO);
     * </pre>
     *
     * @param page      数据库查出来的分页对象
     * @param converter 实体 → VO 的转换函数
     */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> converter) {
        return new PageResult<>(
                page.getRecords().stream().map(converter).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                page.getPages()
        );
    }
}
