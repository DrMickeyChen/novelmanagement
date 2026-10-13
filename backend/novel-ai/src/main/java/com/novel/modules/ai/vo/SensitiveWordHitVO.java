package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 试扫命中的一条词（E1/S7）。
 *
 * <p>★ <b>刻意不带「命中在第几个字」</b>：位置对审核动作没有任何影响
 * （第一层只回答「命中了哪些词、最高几级」，然后据此决定要不要调 AI 复核），
 * 而带上去会让前端忍不住去做高亮 —— <b>那个高亮会用一段错位的下标把管理员引向错误的段落</b>
 * （Java 的 {@code charAt} 下标与 JS 的字符串下标虽然都是 UTF-16 码元、能对上，
 * 但正文里一旦有代理对或前后端任何一处做了截断，就会开始错位，而且不报错）。
 * <p>★ 真要定位，用 {@code word} 自己去正文里搜 —— 那是唯一不会错位的方式。
 *
 * @author MickeyChen
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "试扫命中的一条违禁词")
public class SensitiveWordHitVO {

    @Schema(description = "命中的词")
    private String word;

    @Schema(description = "这个词的等级：1低 2中 3高")
    private Integer level;

    @Schema(description = "分类")
    private String category;
}
