package com.novel.modules.interaction.vo;

import com.novel.modules.interaction.entity.ReadHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阅读进度出参。
 *
 * <p><b>★ 刻意不含 {@code userId}、也不含书名章名。</b>
 * <ul>
 *   <li>{@code userId} —— 调用方就是本人（这个接口只能查到自己的记录），
 *       返回它没有任何用处，却给了「把用户 id 暴露到前端」一个理由。
 *       和 P1 里 {@code PingController} 只返回安全字段是同一条规矩。</li>
 *   <li>书名 / 章名 —— 阅读器打开这本书时手上<b>已经有</b>
 *       {@code GET /api/novels/{id}} 返回的详情（含完整目录），
 *       拿 {@code chapterId} 在本地就能查出「第几章 叫什么」。
 *       在这里再查一次库、拼一遍，是纯粹的重复劳动。</li>
 * </ul>
 * <p>P6 要做「阅读历史列表」时，那一页确实是只拿一组 novelId 去渲染的，
 * 到时再给那个场景单开一个 VO（带书名 / 封面 / 章名）——
 * <b>不要为了将来的那个页面，让现在的阅读器多背三个用不上的字段。</b>
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "阅读进度")
public class ReadHistoryVO {

    @Schema(description = "作品 id")
    private Long novelId;

    @Schema(description = "读到哪一章的 id")
    private Long chapterId;

    @Schema(description = "章内进度百分比 0~100")
    private Integer progress;

    @Schema(description = "最后上报时间")
    private LocalDateTime updateTime;

    public static ReadHistoryVO of(ReadHistory entity) {
        if (entity == null) {
            return null;
        }
        ReadHistoryVO vo = new ReadHistoryVO();
        vo.setNovelId(entity.getNovelId());
        vo.setChapterId(entity.getChapterId());
        vo.setProgress(entity.getProgress());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
