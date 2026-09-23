package com.novel.modules.content.vo;

import com.novel.common.vo.NovelVO;
import com.novel.modules.content.entity.Novel;

/**
 * {@link NovelVO} 的装配点 —— <b>全项目唯一一处把 {@code Novel} 实体填进 {@code NovelVO} 的地方</b>。
 *
 * <h3>★★ 为什么需要这个类（M4 新增）</h3>
 *
 * <p>M4 把 {@code NovelVO} 搬去了 {@code novel-common}（理由见那边的类注释），
 * 而它原来的 {@code of(Novel, ...)} / {@code fillFrom(Novel, ...)} <b>搬不走</b> ——
 * {@code Novel} 是 content 的表实体，必须留在 content。
 * ★ 于是「装配」这个动作留在了这边，本类就是它新的家。
 *
 * <h3>★★★ 为什么用具名 setter，而不是「收展开字段的位置参数」</h3>
 *
 * <p>{@code NovelBriefVO} 搬家时改成了收展开字段（M2），
 * ★ 但<b>本类不能照抄那套</b>，两个理由：
 *
 * <ol>
 *   <li><b>子类需要「填一个已存在的实例」。</b>
 *       {@code NovelDetailVO extends NovelVO}，它的 {@code of()} 要先把
 *       <b>父类那 15 个字段</b>填到 {@code this} 上，再 set 自己独有的 4 个。
 *       静态工厂造不出这个效果，必须要一个实例方法 —— 即 {@link #fill}。</li>
 *   <li>★★ <b>15 个字段里有 7 个是「相邻同类型」的：</b>
 *       {@code id / authorId / categoryId} 三个 {@code Long} 挨在一起，
 *       {@code chapterCount / wordCount / viewCount / isFinish} 四个 {@code Integer} 挨在一起。
 *       写成位置参数的话，传错<b>编译期一个字都不会报</b>，
 *       而后果是接口安静地返回错误的数据（详情页显示别人的作者 id）。
 *       ★ 具名 setter 让这种错误<b>根本写不出来</b>。</li>
 * </ol>
 *
 * <h3>★ {@link #fill} 为什么是包级私有（不是 {@code public}）</h3>
 *
 * <p>它只给同包的 {@code NovelDetailVO} 用。★ 原来的 {@code fillFrom} 是
 * {@code protected}，意图是「外部只能走静态工厂，不该拿个空 VO 自己填」——
 * 现在 {@code protected} 用不了了（父类在 common，不认识 {@code Novel}），
 * 用<b>包级私有</b>接住同一个意图：
 * <ul>
 *   <li>同包的 {@code NovelDetailVO} 能用 ✅</li>
 *   <li>{@code service.impl} 下的 {@code NovelServiceImpl} <b>用不了</b> ——
 *       它只能走 {@link #of} ✅ 这正是我们要的</li>
 * </ul>
 * ★ 注意<b>不能</b>简单地把 {@code fill} 设成 {@code public} 图省事：
 * 那等于把「漏填字段编译器不管」这个坑重新打开。
 *
 * @author MickeyChen
 */
public final class NovelVos {

    /**
     * ★ 工具类，不实例化。
     * <p>和 {@code NovelBriefVO} 那种「静态工厂长在 VO 自己身上」的写法不同，
     * 这里是<b>独立的工具类</b> —— 因为 {@code NovelVO} 已经搬去 common，
     * 它那一侧不该知道 {@code Novel} 这个类型的存在。
     */
    private NovelVos() {
    }

    /**
     * 从实体转换出一个列表项。
     *
     * <p>{@code categoryName} / {@code authorName} 要由调用方查好传进来 ——
     * <b>这个类不查库</b>。理由（照抄自 {@code NovelVO#of} 的原文）：
     * 列表接口一次转 20 条，如果这里内部逐条查分类和作者，就是 40 次查询；
     * 调用方（Service）可以一次批量查完再转。
     * <b>转换方法里带 IO 是 VO 最容易变慢的地方。</b>
     *
     * @param entity       作品实体，不可为 {@code null}
     * @param categoryName 分类名，查不到可传 {@code null}
     * @param authorName   作者昵称，查不到可传 {@code null}
     */
    public static NovelVO of(Novel entity, String categoryName, String authorName) {
        NovelVO vo = new NovelVO();
        fill(vo, entity, categoryName, authorName);
        return vo;
    }

    /**
     * ★★ <b>把实体里属于「列表项」的那部分字段填进给定对象</b> ——
     * 本方法存在是<b>为了让「继承」真的成立</b>。
     *
     * <p>如果没有它，{@code NovelDetailVO} 会写成
     * 「{@code extends NovelVO} + 自己的 {@code of()} 里把父类 15 个字段再赋值一遍」——
     * 那继承就白做了：父类加一个字段，详情那边照样漏。
     * 字段继承解决了「多声明一遍」，<b>但赋值语句的重复要靠这个方法解决</b>。
     *
     * <p>★★ 所以下面这 15 行是<b>唯一</b>一份「实体字段 → VO 字段」的映射，
     * 加字段时只改这里。<b>别再开第二处。</b>
     *
     * @param vo           目标对象，可以是 {@code NovelVO} 也可以是它的子类
     * @param categoryName 分类名，查不到可传 {@code null}
     * @param authorName   作者昵称，查不到可传 {@code null}
     */
    static void fill(NovelVO vo, Novel entity, String categoryName, String authorName) {
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setCover(entity.getCover());
        vo.setAuthorId(entity.getAuthorId());
        vo.setAuthorName(authorName);
        vo.setCategoryId(entity.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setStatus(entity.getStatus());
        vo.setStatusDesc(entity.getStatus() == null ? null : entity.getStatus().getDesc());
        vo.setChapterCount(entity.getChapterCount());
        vo.setWordCount(entity.getWordCount());
        vo.setViewCount(entity.getViewCount());
        vo.setIsFinish(entity.getIsFinish());
        vo.setScore(entity.getScore());
        vo.setLastUpdateTime(entity.getLastUpdateTime());
    }
}
