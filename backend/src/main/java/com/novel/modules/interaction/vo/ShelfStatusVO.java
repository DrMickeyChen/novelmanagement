package com.novel.modules.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 「这本书在不在我的书架」+ 最新收藏数。<b>加入 / 移出 / 查询三个接口共用。</b>
 *
 * <p><b>★ 为什么加入和移出也返回一个「状态」而不返回空：</b>
 * 前端点完按钮必须立刻知道「现在到底是加了还是没加」，否则只能自己猜
 * （假设请求成功 → 把按钮翻转）。而猜测在两种情况下会错：
 * ① 加入一本<b>本来就在书架里</b>的书（幂等，状态没变）——
 * 翻转就变成了「按钮显示未加入，实际已加入」；
 * ② 请求超时但服务端成功了。返回真实状态，前端永远不需要猜。
 *
 * <p><b>★ 为什么连收藏数一起返回：</b>它本来就是这次操作的副产品
 * （{@code collect_count} 在这条路径上刚被重算过，见 {@code ShelfServiceImpl}），
 * 顺手带出来是零成本的。前端因此不用为了刷新一个数字再调一次详情接口 ——
 * 而后端多写的只是 VO 上的一个 {@code set}。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "书架状态")
public class ShelfStatusVO {

    @Schema(description = "作品 id", example = "1")
    private Long novelId;

    /**
     * ★ 一律是 {@code Boolean} 而不是 {@code boolean}：这个类没有「不知道」的情况，
     * 但用包装类型能让「漏 set」在 JSON 里表现为 key 消失（全局配了
     * {@code default-property-inclusion: non_null}）而不是悄悄变成 {@code false}。
     * 布尔字段一不小心漏填，表现正好是「看起来很正常」。
     */
    @Schema(description = "是否在书架中", example = "true")
    private Boolean onShelf;

    /** 重算后的收藏数。★ 作品不可见时为 null（详情页的收藏数来自详情接口） */
    @Schema(description = "收藏数", example = "87")
    private Integer collectCount;

    public static ShelfStatusVO of(Long novelId, boolean onShelf, Integer collectCount) {
        ShelfStatusVO vo = new ShelfStatusVO();
        vo.setNovelId(novelId);
        vo.setOnShelf(onShelf);
        vo.setCollectCount(collectCount);
        return vo;
    }
}
