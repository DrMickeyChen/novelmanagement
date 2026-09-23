package com.novel.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 统一分页响应体。
 *
 * <pre>
 * {
 *   "records": [ ... ],
 *   "total": 137,    总条数
 *   "current": 1,    当前第几页
 *   "size": 10,      每页多少条
 *   "pages": 14      总共几页
 * }
 * </pre>
 *
 * <p><b>★★ 本类【不引用任何 ORM 类型】—— 这是 M4 用一次真实故障换来的约束，别加回去。</b>
 * 两个装配工厂分成两处：{@link PageResults}（零依赖，谁都能用）和
 * {@link MybatisPageResults}（{@code IPage} 适配器）。★ 它们<b>不能合并成一个类</b> ——
 * {@code Class.getDeclaredMethods()} 会解析<b>每个方法签名</b>里的类型，
 * 所以只要本类的方法参数里出现 {@code IPage}，一个<b>没有 MyBatis-Plus 的服务</b>
 * （{@code novel-search}）反序列化它时就会 {@code NoClassDefFoundError}，
 * 哪怕那个方法一次都没被调用。★ 详见 {@link MybatisPageResults} 的类注释。
 *
 * <p><b>为什么不直接返回 MyBatis-Plus 的 {@code Page}：</b>
 * {@code Page} 里还带着 {@code orders}、{@code optimizeCountSql}、{@code searchCount}
 * 这些内部字段，序列化出去是一堆前端用不上的噪音；而且第二期拆微服务后
 * {@code Page} 是 ORM 的类，不该跨服务传递。统一成这个五字段结构，
 * 前端的分页组件（Element Plus 的 {@code el-pagination}）可以直接对接。
 *
 * @param <T> 列表元素类型
 * @author MickeyChen
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "当前页数据")
    private List<T> records;

    @Schema(description = "总条数", example = "137")
    private Long total;

    @Schema(description = "当前页码，从 1 开始", example = "1")
    private Long current;

    @Schema(description = "每页条数", example = "10")
    private Long size;

    @Schema(description = "总页数", example = "14")
    private Long pages;

    /** 空分页。查不到数据时返回它，前端不用做 null 判断 */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(Collections.emptyList(), 0L, 1L, 10L, 0L);
    }
}
