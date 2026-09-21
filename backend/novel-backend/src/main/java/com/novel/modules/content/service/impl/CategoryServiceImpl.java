package com.novel.modules.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.content.dto.CategorySaveDTO;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.entity.NovelCategory;
import com.novel.modules.content.mapper.NovelCategoryMapper;
import com.novel.modules.content.mapper.NovelMapper;
import com.novel.modules.content.service.CategoryService;
import com.novel.modules.content.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分类服务实现。
 *
 * <p><b>★ 全项目最小的一个 Service，但它踩了两个「必现」的坑</b>，
 * 一个在删除（唯一键 vs 逻辑删除，见 {@code NovelCategoryMapper}），
 * 一个在重名校验（见 {@link #requireNameNotUsed}）。两个都不报错、只出错数据。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    /** 启用状态。★ 写成常量而不是散落的 1 —— 停用/启用的判断会出现在好几个地方 */
    private static final int STATUS_ENABLED = 1;

    private final NovelCategoryMapper categoryMapper;
    private final NovelMapper novelMapper;

    /* ==================== 查询 ==================== */

    @Override
    public List<CategoryVO> listEnabled() {
        // ★ 过滤在 WHERE 里，不是查出来再 if。见 CategoryService#listEnabled 的注释
        return listByStatus(STATUS_ENABLED);
    }

    @Override
    public List<CategoryVO> listAll() {
        // 传 null → 条件不生效 → 全部（MP 的 eq 第一个参数是 boolean，为 false 时整条条件跳过）
        return listByStatus(null);
    }

    /**
     * 按状态查分类列表。
     *
     * @param status {@code null} 表示不限状态
     */
    private List<CategoryVO> listByStatus(Integer status) {
        LambdaQueryWrapper<NovelCategory> wrapper = new LambdaQueryWrapper<NovelCategory>()
                .eq(status != null, NovelCategory::getStatus, status)
                .orderByAsc(NovelCategory::getSort)
                /*
                 * ★ 必须再按 id 排一次，不能只按 sort 排。
                 *
                 * sort 上【没有唯一约束】，8 个种子分类的 sort 都是 1、2、3……
                 * 但管理员完全可以把两个分类都设成 sort = 10。
                 * MySQL 对「排序值相同的行」返回顺序【不保证稳定】——
                 * 同一页数据，刷新两次可能顺序不同。
                 * 现象是前端列表「自己会跳」，而这是最难查的一类 bug：
                 * 后端每次执行的都是同一条 SQL，看起来完全正常。
                 *
                 * 加 id 兜底之后，排序就是确定的了（id 是主键，全局唯一）。
                 */
                .orderByAsc(NovelCategory::getId);

        return categoryMapper.selectList(wrapper).stream()
                .map(CategoryVO::of)
                .toList();
    }

    /* ==================== 写 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveDTO dto) {
        String name = dto.getName().trim();
        requireNameNotUsed(name, null);

        NovelCategory entity = new NovelCategory();
        entity.setName(name);
        entity.setSort(dto.getSort());
        entity.setStatus(dto.getStatus());

        try {
            categoryMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            /*
             * ★ 上面那个 requireNameNotUsed 是【尽力而为】的友好提示，
             * 真正拦住重名的是 uk_name 这个唯一键。
             *
             * 两个人同时点「新增同名的分类」时，两个请求都能通过上面的检查
             * （各自看的时候对方还没提交），然后其中一个在 INSERT 时撞键。
             * 不接这个异常的话，它会掉到 GlobalExceptionHandler 的
             * Exception.class 兜底，用户看到 500「服务器开小差了」——
             * 而实际情况只是「名字重复了」，完全该给 6002。
             *
             * 所以这里是【把数据库的约束报错翻译成业务错误】，
             * 不是重复校验。上面的检查负责第一时间的友好提示，
             * 这里负责兜住并发那一下。
             */
            log.warn("新增分类时撞唯一键（并发重名） | name={}", name);
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXISTS, "分类「" + name + "」已存在");
        }

        log.info("新增分类成功 | id={} name={} sort={} status={}",
                entity.getId(), name, dto.getSort(), dto.getStatus());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategorySaveDTO dto) {
        NovelCategory exist = requireCategory(id);
        String name = dto.getName().trim();
        // ★ 排除自己，否则「只改排序、名字不动」会被自己判成重名
        requireNameNotUsed(name, id);

        /*
         * ★ 用一个【只带要改的字段】的新对象 updateById，而不是
         * 「查出来的 exist 改几个字段再 updateById(exist)」。
         *
         * MP 的 updateById 默认只更新【非 null 字段】。两种写法在这里结果一样，
         * 但第二种有个隐患：exist 是从库里查出来的，带上了 createTime 等
         * 所有字段，将来谁在 exist 上多 set 了一个字段（比如为了别的地方用），
         * 这个 update 就顺手把它也写回库里了 —— 而改动的地方在几行之外，
         * review 时看不出来。只带要改的字段，UPDATE 的 SET 子句就是所见即所得。
         */
        NovelCategory update = new NovelCategory();
        update.setId(id);
        update.setName(name);
        update.setSort(dto.getSort());
        update.setStatus(dto.getStatus());

        try {
            categoryMapper.updateById(update);
        } catch (DuplicateKeyException e) {
            // 同 create：并发改名的兜底
            log.warn("编辑分类时撞唯一键（并发重名） | id={} name={}", id, name);
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXISTS, "分类「" + name + "」已存在");
        }

        log.info("编辑分类成功 | id={} name={} sort={} status={}",
                id, name, dto.getSort(), dto.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        NovelCategory exist = requireCategory(id);

        /*
         * ★ 引用检查用 MP 的方法，不手写 SQL。
         *
         * 理由是 @TableLogic：selectCount 生成的 SQL 会自动带上 deleted = 0，
         * 而手写 @Select("SELECT COUNT(*) FROM novel WHERE category_id = ?")
         * 会把【已逻辑删除的作品】也算进来。
         * 症状是「这个分类下面明明一本书都没有，却删不掉」，
         * 而且界面上根本看不到那些被删掉的书，无从查起。
         * （这个坑 P1 在 SysUserMapper 上已经踩过一次，见那边的注释。）
         *
         * ★ 口径不限作品状态：草稿、待审、已下架的书都算「还挂着这个分类」。
         * 分类被删掉之后它们的 category_id 就指向一个查不到的 id，
         * 前端显示分类名时是一片空白。
         */
        Long refCount = novelMapper.selectCount(
                new LambdaQueryWrapper<Novel>().eq(Novel::getCategoryId, id));
        if (refCount != null && refCount > 0) {
            throw new BusinessException(ResultCode.CATEGORY_HAS_NOVELS,
                    "「" + exist.getName() + "」下还有 " + refCount + " 部作品，"
                            + "请先转移或删除这些作品；若只是想暂停使用，请把它改成「停用」");
        }

        // ★ 不是 deleteById —— 那会占着 uk_name 不放，见 NovelCategoryMapper 的注释
        int affected = categoryMapper.logicDeleteAndFreeName(id);
        if (affected == 0) {
            /*
             * 走到这里说明：在我们查完之后、删之前，另一个请求已经把它删掉了。
             *
             * ★ 这里【不抛异常】，当作删除成功返回。
             * 因为用户想要的结果（这个分类没了）已经达成了，
             * 给他弹一个「分类不存在」的红色提示只会让人以为操作失败、
             * 然后再点一次 —— 而第二次同样会得到这个提示，陷入死循环般的困惑。
             * 「幂等删除」在这里比「如实报错」更符合用户预期。
             *
             * 但必须留一条 warn：这是个并发事件，出现了就该被人看到。
             * 如果这条日志开始频繁出现，说明前端的列表数据太旧了
             * （管理员对着几分钟前的页面在操作），那是另一个问题。
             */
            log.warn("删除分类时已被他人删除（并发） | id={} name={}", id, exist.getName());
            return;
        }

        log.info("删除分类成功 | id={} name={}", id, exist.getName());
    }

    /* ==================== 私有工具 ==================== */

    /**
     * 取分类，不存在就抛 {@code CATEGORY_NOT_FOUND(6001)}。
     *
     * <p>{@code selectById} 会自动带 {@code deleted = 0}（{@code @TableLogic}），
     * 所以「已删除的分类」在这里等同于「不存在」——
     * 这正是我们要的：已删除的分类不该还能被编辑。
     */
    private NovelCategory requireCategory(Long id) {
        NovelCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    /**
     * 校验分类名没被占用。
     *
     * <p><b>★ 这个方法的两个细节都不是多余的：</b>
     * <ol>
     *   <li><b>用 {@code selectCount} 而不是手写 SQL</b> ——
     *       {@code @TableLogic} 会自动补 {@code deleted = 0}。
     *       漏了它的话，删掉一个叫「玄幻」的分类之后，
     *       再建叫「玄幻」的会被判成重名，而管理端列表里根本没有那个分类，
     *       用户会觉得「系统在骗我」。</li>
     *   <li><b>排除自己（{@code excludeId}）</b> —— 编辑时不排除自己，
     *       「只改排序」的请求会被自己的名字判成重复。</li>
     * </ol>
     *
     * <p><b>★ 这是「尽力而为」的检查，不是并发安全的。</b>
     * 真正的防线是 {@code uk_name} 唯一键，所以调用方还要接住
     * {@code DuplicateKeyException}（见 {@link #create}）。
     * 这不是重复劳动：这里负责「绝大多数情况下的友好提示」，
     * 唯一键负责「并发那一下不写进脏数据」。
     *
     * @param excludeId 要排除的分类 id（编辑时传自己），新增时传 {@code null}
     */
    private void requireNameNotUsed(String name, Long excludeId) {
        Long count = categoryMapper.selectCount(new LambdaQueryWrapper<NovelCategory>()
                .eq(NovelCategory::getName, name)
                .ne(excludeId != null, NovelCategory::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXISTS, "分类「" + name + "」已存在");
        }
    }
}
