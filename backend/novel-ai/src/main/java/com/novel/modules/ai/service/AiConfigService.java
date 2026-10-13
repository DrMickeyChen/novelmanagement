package com.novel.modules.ai.service;

import com.novel.modules.ai.dto.AiConfigSaveDTO;
import com.novel.modules.ai.vo.AiConfigVO;

/**
 * AI 配置的读写与闸门（E1）。
 *
 * <h3>★★★ 这个接口上「读配置」有【两个】方法，它们的区别是整条链的安全边界</h3>
 *
 * <table border="1">
 *   <tr><th>方法</th><th>返回什么</th><th>谁调</th><th>失败时</th></tr>
 *   <tr>
 *     <td>{@link #requireUsable()}</td>
 *     <td>{@link AiRuntimeConfig}（<b>不含 Key</b>）</td>
 *     <td>AI 前置闸门、管理端展示</td>
 *     <td>停用 → 5001；没配 Key → 5002</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #decryptCurrentApiKey()}</td>
 *     <td>明文 Key</td>
 *     <td><b>只在真正发 HTTP 请求的前一行</b></td>
 *     <td>同上，外加解密失败 → 500</td>
 *   </tr>
 * </table>
 *
 * <p>★★ 拆成两个不是洁癖：{@code AiRuntimeConfig} 是<b>可以被安全打印</b>的，
 * 而明文 Key 不是。把两者混成一个返回值，就等于把「别打印它」这条纪律
 * 交给每一个未来的调用者去记得 —— 见 {@code AiRuntimeConfig} 的类注释。
 *
 * <p>★ 这几个方法都会<b>各自读一次库</b>。这是有意的（配置行只有一行、单行主键级查询），
 * 换取的是一句很值钱的话：<b>「拿到明文 Key 的那一行代码，在源码里搜得到」</b>。
 *
 * <p>★ {@link #dailyLimit()} 是第三个读方法，但它<b>不碰 Key</b>（返回一个 {@code int}），
 * 所以它不在上面那条安全边界之内 —— 见它自己的注释。
 *
 * @author MickeyChen
 */
public interface AiConfigService {

    /** 管理端读配置。★ 返回掩码，永不返回 Key（连密文都不返回） */
    AiConfigVO get();

    /** 管理端保存配置。★ {@code apiKey} 不传 = 保持原值；{@code clearApiKey=true} 才清空 */
    AiConfigVO save(AiConfigSaveDTO dto);

    /**
     * 每用户每日的调用上限（{@code ai_config.daily_limit}）—— <b>唯一的真相来源，且【不抛异常】</b>。
     *
     * <h4>★★★ 为什么它不是 {@link #requireUsable()} 的一部分</h4>
     *
     * <p>因为它服务于 {@code GET /api/ai/quota}，而那个接口<b>必须在 AI 停用时也能正常返回</b>。
     * <ul>
     *   <li>种子里 {@code ai_config.enabled = 0}，也就是<b>默认状态就是停用</b>；</li>
     *   <li>而 P4 的 {@code AiPanel} 一打开就会调它，失败时会把整个额度区
     *       {@code v-if} 掉 —— 于是「新克隆的仓库不配任何东西就能跑」
     *       会在界面上表现为<b>额度那一块凭空消失</b>，且没有任何提示。</li>
     * </ul>
     * ★ 换句话说：{@link #requireUsable()} 回答的是「<b>能不能生成</b>」（停用就该抛），
     * 这个方法回答的是「<b>上限是多少</b>」（停用也照样有上限，只是用不到）。
     * <b>两个问题不一样，所以不能共用一个会抛的方法。</b>
     *
     * <p>★ 读到 0 行仍然抛（那是种子被删了，见实现里的 {@code currentRow()}）——
     * 唯一被容忍的异常是「列值为 NULL」，回落 50：DDL 上它是
     * {@code INT NOT NULL DEFAULT 50}，出现 NULL 只可能是有人手工改过，
     * 而这里返回 0 会让额度区显示「0/0」—— 一个看起来像「已用完」的假象。
     */
    int dailyLimit();

    /**
     * 取「一次真实调用需要的参数」，<b>顺带把两个前置条件判掉</b>。
     *
     * @throws com.novel.common.exception.BusinessException
     *         停用 → {@code AI_DISABLED(5001)}；没配 Key → {@code AI_API_KEY_MISSING(5002)}
     */
    AiRuntimeConfig requireUsable();

    /**
     * 取当前配置里那把 Key 的<b>明文</b>。
     *
     * <p>★★ <b>调用点必须紧挨着发请求那一行</b>，且结果不要存进任何字段、不要进日志。
     * 这是全项目唯一会返回明文密钥的方法。
     */
    String decryptCurrentApiKey();
}
