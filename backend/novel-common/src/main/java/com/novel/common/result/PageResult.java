package com.novel.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

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

    /**
     * 把 MyBatis-Plus 的 {@code IPage} 转成统一分页体。
     *
     * <pre>
     * Page&lt;Novel&gt; page = novelMapper.selectPage(new Page&lt;&gt;(current, size), wrapper);
     * return Result.success(PageResult.of(page));
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
     * PageResult&lt;NovelVO&gt; vo = PageResult.of(page, this::toVO);
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

    /** 空分页。查不到数据时返回它，前端不用做 null 判断 */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(Collections.emptyList(), 0L, 1L, 10L, 0L);
    }
}
