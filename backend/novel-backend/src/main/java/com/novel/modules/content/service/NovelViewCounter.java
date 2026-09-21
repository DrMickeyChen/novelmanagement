package com.novel.modules.content.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novel.common.constant.RedisKeyConstant;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 阅读量计数器：点击时只累加 Redis，定时批量落库。
 *
 * <p>这个类解决的是一个很具体的问题：<b>阅读量是全站写入最频繁的字段，
 * 但它的准确性要求极低</b>。几百个人同时打开同一本书，没必要产生几百条
 * {@code UPDATE novel SET view_count = view_count + 1} —— 每条都要抢行锁、
 * 写 binlog、刷 redo，而读者看到的数字是 1280 还是 1283 根本没人关心。
 *
 * <p>所以：点击只做一次 Redis {@code INCR}（内存操作，微秒级、无锁竞争），
 * 每 30 秒把积压的增量合并成一条 {@code UPDATE} 落库。
 *
 * <p><b>带来的一致性损失（这是刻意的，面试会问）：</b>
 * <ul>
 *   <li>Redis 挂了、或者进程在两次落库之间被杀，<b>这段时间的阅读量会丢</b>。</li>
 *   <li>因此 {@code novel.view_count} 是<b>最终一致</b>的近似值，不是精确值。
 *       涉及钱的计数（订单、余额）绝不能这么做。</li>
 * </ul>
 *
 * <h3>★★ 为什么必须用 {@code StringRedisTemplate}</h3>
 * 注入 {@code RedisTemplate<String, Object>}（{@code RedisConfig} 里那个）
 * 然后 {@code opsForValue().increment()} 是个经典陷阱：它的 value serializer 是
 * {@code GenericJackson2JsonRedisSerializer} + {@code activateDefaultTyping}，
 * 写进去的不是整数 {@code 1}，而是
 * {@code {"@class":"java.lang.Integer","value":1}}。
 * Redis 的 {@code INCR} 命令要求 value 是十进制数字字符串，遇到这坨 JSON 会报
 * {@code ERR value is not an integer or out of range}。
 *
 * <p>{@code StringRedisTemplate} 是 Spring Boot 自动配置好的（见
 * {@code RedisConfig} 里那句「两个可以共存，不要覆盖默认的」），
 * 它的 value serializer 是 {@code StringRedisSerializer}，存的就是纯数字。
 *
 * <h3>★★ 落库顺序：先 SREM 认领，再 GETDEL 取数</h3>
 * 见 {@link #flush()} 里的长注释。<b>顺序反了会永久丢数据</b>。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelViewCounter {

    private final StringRedisTemplate stringRedisTemplate;
    private final NovelMapper novelMapper;

    /**
     * 记一次阅读。由 {@code POST /api/novels/{id}/view} 调用。
     *
     * <p><b>只用 Redis，不碰数据库</b> —— 这是这个接口能匿名开放的前提
     * （不落库、不产生数据、不记录调用者）。
     *
     * <p>★ <b>两条命令的顺序不能反：先 INCR，再 SADD。</b>
     * 如果先 SADD 再 INCR，中间恰好夹了一次落库：
     * 落库任务 {@code SREM} 认领成功 → {@code GETDEL} 取到 {@code null}（还没 INCR）
     * → 这一轮空转；随后我们的 INCR 才把值写进去，而脏集合里的标记已经被摘掉了
     * → <b>这个值要等到这本书下次被人点击才会被落库</b>。
     * 反过来先 INCR 再 SADD 的最坏情况只是「值在 Redis 里多躺一轮」，无害。
     *
     * @param novelId 作品 id。<b>调用方必须先校验它存在且已上架</b> ——
     *                这里不做校验，因为匿名接口需要一个地方挡住
     *                「POST 一百万个随机 id 造出一百万个 Redis key」。
     */
    public void record(Long novelId) {
        try {
            stringRedisTemplate.opsForValue().increment(RedisKeyConstant.NOVEL_VIEWS + novelId);
            stringRedisTemplate.opsForSet().add(RedisKeyConstant.NOVEL_VIEW_DIRTY, String.valueOf(novelId));
        } catch (Exception e) {
            /*
             * ★ 埋点失败【绝不能让读者的页面出错】。
             * 阅读量丢一点无所谓，读者打不开书才是事故 —— 所以这里吞掉异常，
             * 只记一条 WARN。这个接口的调用方也在 Controller 里做了同样的兜底。
             */
            log.warn("阅读量埋点失败（已忽略，不影响读者） | novelId={} error={}", novelId, e.getMessage());
        }
    }

    /**
     * 把积压的阅读量增量刷进数据库。每 30 秒一轮。
     *
     * <p>周期取 30 秒是为了「验收时手点两下就能在榜单上看到变化」，
     * 同时这个写入频率（每分钟最多 2 条 UPDATE）对数据库毫无压力。
     * 想调就改 {@code PT30S}，用 ISO-8601 的 Duration 写法，别写裸数字
     * （裸数字的单位是毫秒，{@code 30} 会变成每 30 毫秒跑一次）。
     *
     * <h3>★★ 为什么是「先 SREM 认领，再 GETDEL 取数」</h3>
     * 这两步的先后决定了并发下会不会<b>永久丢数据</b>。反过来写
     * （先 {@code GETDEL} 再 {@code SREM}）会打开一个窗口：
     * <pre>
     *   ① 我们 GETDEL 拿到 100，key 被删掉
     *   ② 一个读者此刻点击 → INCR 把它变成 1，并 SADD 把 id 放回脏集合
     *   ③ 我们紧接着 SREM，把【刚才那个读者刚放回去的标记】删掉了
     *   → 那 1 次阅读永远不会被落库，而且脏集合被清得干干净净，
     *     再没有任何一轮任务会来救它。
     * </pre>
     * 先 SREM 就没有这个窗口：{@code SREM} 返回 1 表示「这一轮这个 id 归我」，
     * 它本身就是一把分布式锁；返回 0 说明别的实例已经领走了，直接跳过。
     *
     * <h3>★ 由此推出的一条：不要在循环末尾再补一次 SREM</h3>
     * 「反正已经处理完了，再 SREM 一次保险」是错的，而且是<b>有害的</b>：
     * 我们处理期间如果有读者点击这本书，他会把 id 重新 SADD 进脏集合
     * （那是他 INCR 出来的、<b>尚未落库</b>的新增量）。收尾那一刀会把这个
     * 新标记删掉，那笔增量就要等到这本书下次被人点击才会落库。
     * 认领动作在循环开头已经做过一次，一次就够了。
     *
     * <h3>★★ 失败方向是刻意选的：宁可少算，不可多算</h3>
     * 数据库更新失败时（连接断了、连接池满了），<b>不做补偿、不回滚 Redis</b>，
     * 只记一条 WARN 把丢失的量写进日志。理由：
     * <ul>
     *   <li>少算的补偿是时间 —— 这本书下次有人读时会重新 INCR + SADD，
     *       {@code GETDEL} 会把积压的一起捞出来，是<b>自愈</b>的。</li>
     *   <li>多算没有任何办法修正。而「UPDATE 已经提交、但响应回不来」
     *       这种情况（网络抖动、连接被中间件掐断）是会真实发生的 ——
     *       如果这时去补偿性地把增量写回 Redis，就会重复累加。
     *       阅读量虚高是没法人工核对回来的。</li>
     * </ul>
     *
     * <p>★ 这个类<b>没有用</b> {@code RedisKeyConstant.LOCK_PREFIX}：上面的
     * {@code SREM} 已经提供了互斥，再加一层锁是多余的。但要注意这个结论
     * 只对「消费型」的 key 成立（取走就没了）。哪天改成「读出来、算一下、
     * 再更新」的形态，这个原子性立刻消失，必须换成真正的分布式锁。
     */
    @Scheduled(fixedDelayString = "PT30S")
    public void flush() {
        Set<String> dirtyIds;
        try {
            dirtyIds = stringRedisTemplate.opsForSet().members(RedisKeyConstant.NOVEL_VIEW_DIRTY);
        } catch (Exception e) {
            /*
             * Redis 连不上。★ 整轮跳过，不要往下走 —— 否则下面的 SREM/GETDEL
             * 会各自抛一次异常，日志被刷屏，真正的原因反而被淹掉。
             */
            log.warn("读取浏览量脏集合失败，本轮跳过 | error={}", e.getMessage());
            return;
        }

        if (dirtyIds == null || dirtyIds.isEmpty()) {
            // 大部分轮次都会走到这里：没人读书就没有增量。
            // 用 debug 而不是 info —— 每 30 秒一条 info 日志会把有用的一行冲走。
            log.debug("浏览量落库：无增量");
            return;
        }

        int flushed = 0;
        long totalDelta = 0;

        for (String rawId : dirtyIds) {
            Long novelId = parseId(rawId);
            if (novelId == null) {
                // 脏集合里混进了非数字成员（有人手工 SET 或写入格式变了）。
                // 认领掉，免得每一轮都把它捞出来重试一次。
                stringRedisTemplate.opsForSet().remove(RedisKeyConstant.NOVEL_VIEW_DIRTY, rawId);
                log.warn("浏览量脏集合里有非法的 novelId，已清理 | value={}", rawId);
                continue;
            }

            // ★★ 认领。返回 1 才是这一轮归我处理；0 表示别的实例已经领走了。
            Long claimed = stringRedisTemplate.opsForSet()
                    .remove(RedisKeyConstant.NOVEL_VIEW_DIRTY, rawId);
            if (claimed == null || claimed == 0L) {
                continue;
            }

            // ★ 在 try 外面声明：下面 catch(NumberFormatException) 要打印这个坏值，
            //   而 GETDEL 已经把 key 删了，那时候再回去读是读不到的。
            String raw = null;
            try {
                // ★ GETDEL：取值和删除是一条命令，中途不会有别人插进来。
                //   用 GET 再 DEL 会在两条命令之间丢掉并发 INCR 的量。
                raw = stringRedisTemplate.opsForValue()
                        .getAndDelete(RedisKeyConstant.NOVEL_VIEWS + novelId);

                if (raw == null) {
                    // 认领了但值不在：说明这一轮认领之前有人把值也取走了（罕见），
                    // 或者 key 被手工删了。不是错误，跳过。
                    log.debug("浏览量落库：认领到 id 但无增量 | novelId={}", novelId);
                    continue;
                }

                int delta = Integer.parseInt(raw);
                if (delta <= 0) {
                    // INCR 只增不减，出现 0 或负数说明 key 被人手工改过。
                    log.warn("浏览量增量非正数，已丢弃 | novelId={} raw={}", novelId, raw);
                    continue;
                }

                if (updateViewCount(novelId, delta)) {
                    flushed++;
                    totalDelta += delta;
                } else {
                    // ★ 影响 0 行 = 这本书已经不存在了（被物理删除，或 deleted=1）。
                    //   不是错误，但我们确实丢了一次增量，所以记 WARN 而不是 debug。
                    log.warn("浏览量落库：目标作品不存在，本次增量丢弃 | novelId={} delta={}", novelId, delta);
                }
            } catch (NumberFormatException e) {
                /*
                 * 负向用例 15：手工把 novel:novel:views:1 设成 "abc"。
                 * 单独 catch 是因为它【不代表 Redis 或数据库有问题】，
                 * 只是这个 key 的值坏了 —— 后面的 id 应该继续处理，
                 * 不能让一个坏 key 中断整轮落库。
                 */
                log.warn("浏览量落库：值不是整数，已丢弃 | novelId={} raw={}", novelId, raw);
            } catch (Exception e) {
                /*
                 * ★★ 数据库更新失败。按上面的取舍【不做补偿】：
                 * 丢失的量写进日志，运维可以照着核对，但绝不写回 Redis。
                 * 注意这里不能让异常冒出去 —— 冒出去会中断整个 for 循环，
                 * 剩下几十本书的增量会全部留在脏集合里等下一轮（虽然最终
                 * 不会丢，但会让这一轮的效果看起来「只刷了一半」）。
                 */
                log.warn("浏览量落库失败，本次增量丢弃（宁可少算，不可多算） | novelId={} error={}",
                        novelId, e.getMessage(), e);
            }
        }

        if (flushed > 0) {
            log.info("浏览量落库完成 | 作品数={} 增量合计={}", flushed, totalDelta);
        }
    }

    /**
     * 把增量加到 {@code novel.view_count} 上。
     *
     * <p>用 {@code setSql} 而不是先查后写：{@code view_count = view_count + ?}
     * 由数据库自己完成读改写，天然原子，不会和另一个实例或读者的操作互相覆盖。
     *
     * <p>★ <b>{@code {0}} 是占位符，不是字符串拼接。</b>
     * MP 会把它转成 {@code #{ew.paramNameValuePairs.MPGENVAL1}} 的预编译参数。
     * 写成 {@code setSql("view_count = view_count + " + delta)} 虽然 delta
     * 是解析出来的 int、当下安全，但那是「靠调用方保证安全」的写法 ——
     * 全仓不出现任何拼 SQL 的地方，后来人才不会照着抄出一个注入口
     * （对照 {@code NovelServiceImpl} 里拒绝用 {@code .last("LIMIT " + n)} 的理由）。
     *
     * <p>★ 这里传的 entity 是 {@code null}，所以 {@code MybatisMetaObjectHandler}
     * 的 {@code updateFill} <b>不会执行</b>，{@code update_time} 保持不动。
     * 这是想要的：阅读量涨了不代表「这本书的信息被修改了」，
     * 被人每读一下就更新一次 {@code update_time} 会让这个字段失去意义。
     * （{@code syncNovelStat} 同样传 null entity，但那边是<b>必须</b>传 ——
     * 它要显式把 {@code last_chapter_id} 写成 null，理由见那个方法的注释。）
     *
     * <p>★ 条件里的 {@code deleted = 0} 由 {@code @TableLogic} 自动补上，
     * 这就是 {@code NovelMapper} 保持空接口的价值。
     *
     * @return 是否命中了一行。false 表示作品不存在或已被删除。
     */
    private boolean updateViewCount(Long novelId, int delta) {
        LambdaUpdateWrapper<Novel> update = new LambdaUpdateWrapper<Novel>()
                .eq(Novel::getId, novelId)
                .setSql("view_count = view_count + {0}", delta);
        return novelMapper.update(null, update) > 0;
    }

    /** 脏集合成员解析成 id。不是数字就是脏数据，交给调用方清理。 */
    private Long parseId(String rawId) {
        try {
            return Long.valueOf(rawId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
