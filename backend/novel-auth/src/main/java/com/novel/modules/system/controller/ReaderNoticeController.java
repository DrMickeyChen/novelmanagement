package com.novel.modules.system.controller;

import com.novel.common.result.Result;
import com.novel.modules.system.service.NoticeService;
import com.novel.modules.system.vo.NoticeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 读者端 —— 公告。
 *
 * <p><b>★★ 这个类只有一条 GET，而它需要 {@code SecurityConfig} 里一条
 * 单独的匿名白名单。这是全项目仅有的「新路径必须回去改那个文件」的情况之一。</b>
 * <pre>
 *   .requestMatchers(HttpMethod.GET, "/api/notices").permitAll()
 * </pre>
 * 三个坑，都写在 {@code SecurityConfig} 的对应位置：
 * <ol>
 *   <li><b>必须带 {@code HttpMethod.GET}</b> —— 白名单条目默认是
 *       <b>方法无关</b>的。只写路径等于把将来任何 {@code POST /api/notices}
 *       一起匿名放行，而那很可能是一个「发公告」的写接口。</li>
 *   <li><b>必须排在 {@code .anyRequest().authenticated()} 之前</b> ——
 *       顺序错了规则不生效，而且<b>没有任何报错</b>。</li>
 *   <li><b>路径是精确的 {@code /api/notices}，不带任何通配符</b> ——
 *       于是将来新增 {@code GET /api/notices/{id}} 时它<b>不会</b>被顺手放行，
 *       那是个需要单独判断的决定。</li>
 * </ol>
 *
 * <p><b>★ 为什么单独一个类，而不是塞进 {@code AdminNoticeController}：</b>
 * 那边整个类都在 {@code /api/admin/**} 下、由 {@code hasRole("ADMIN")} 兜底。
 * 两个接口的<b>访问控制方式完全不同</b>（一个靠 URL 前缀、一个靠白名单），
 * 混在一个类里之后，看类名已经判断不出「这个类的接口是公开的吗」——
 * 将来谁给那个类加一个 {@code @PreAuthorize("hasRole('ADMIN')")}，
 * 读者端首页会立刻变成 401，而改动的人完全想不到自己在改公开接口。
 *
 * <p><b>★ 匿名开放它是安全的</b>，因为它只读、且只读「已发布」的公告 ——
 * 「某条公告草稿的标题是什么」这种信息不会从这里漏出去
 * （过滤在 SQL 的 {@code WHERE} 里，见 {@code NoticeServiceImpl#listPublished}）。
 *
 * @author MickeyChen
 */
@Tag(name = "31. 读者端 · 公告", description = "已发布公告列表。**无需登录**")
@RestController
@RequiredArgsConstructor
public class ReaderNoticeController {

    private final NoticeService noticeService;

    @Operation(
            summary = "已发布的公告列表（最新在前）",
            description = """
                    **无需登录。** 读者端首页顶上那条横幅的唯一数据来源。

                    ★ **这不是分页接口**：query 参数是 `limit` 而不是 `page/size`。
                    横幅要的是「最新几条」，不是一份可以翻页的档案 ——
                    做成分页的话前端要维护 page/size 两个状态，
                    而第 2 页的数据<b>永远没人看</b>。

                    - `limit`：最多几条，**会被夹到 1~20**，默认 `5`。
                      传 `0` / 负数 / 超大值都不会报错，会被夹进合法区间

                    ★ 只返回 `status = 1`（已发布）的，按 `publishTime` 倒序。
                    **下线（`status = 0`）和已删除的都不会出现。**

                    ★ 返回 `content`（正文）—— 公告**没有详情页**，
                    横幅是它唯一的展示位，只给标题等于没发。

                    ★ **不返回 `status`**：这一列在读者侧恒等于 1，
                    返回它只会让前端写出永远为真的分支。

                    ★ 没有任何公告时返回**空数组**（不是 404、不是 null）——
                    这是完全正常的状态（新站、或者运营还没发过东西），
                    前端应当安静地不渲染横幅，而不是弹一个错误。""")
    @GetMapping("/api/notices")
    public Result<List<NoticeVO>> listPublished(
            @Parameter(description = "最多返回几条，会被夹到 1~20，默认 5")
            @RequestParam(defaultValue = "5") int limit) {

        return Result.success(noticeService.listPublished(limit));
    }
}
