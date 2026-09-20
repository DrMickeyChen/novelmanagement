package com.novel.modules.content.service;

import com.novel.modules.content.dto.CategorySaveDTO;
import com.novel.modules.content.vo.CategoryVO;

import java.util.List;

/**
 * 分类服务。读者端 / 管理端两处用，作者端不用（作者只是选一个分类）。
 *
 * <p><b>★ 接口和实现分成两个文件，是 P1 就定下的分层样板</b>
 * （见 {@code modules/system/} 下的 {@code AuthService} / {@code AuthServiceImpl}）。
 * 理由不是「Spring 要求这样」，而是：Controller 依赖接口，
 * 第二期拆服务时把 {@code Impl} 换成「Feign 调远端」，Controller 一行都不用改。
 * <b>直接注入 Impl 的话，这个替换点就没有了。</b>
 *
 * <p><b>★ 分类为什么没有分页：</b>
 * 它是一个<b>字典表</b>，量级是「十几个」，不是「几万条」。
 * 给它做分页会让前端的下拉框、筛选器全部要处理分页 —— 纯粹是自找麻烦。
 * 真有哪天分类上千了，再说（那时它已经不是「分类」而是「标签」了）。
 *
 * @author MickeyChen
 */
public interface CategoryService {

    /**
     * 读者端列表 —— <b>只返回启用的分类</b>。
     *
     * <p>★ 过滤发生在 {@code WHERE} 里，不是查出来再筛。
     * 查出来再筛的话，停用的分类依然会出现在接口返回里，
     * 打开 F12 就能看见 —— 包括那些还没准备好对外公布的。
     */
    List<CategoryVO> listEnabled();

    /**
     * 管理端列表 —— <b>全部</b>，含已停用的。
     *
     * <p>★ 必须含停用的：否则管理员停用一个分类之后就再也看不到它，
     * 也就<b>没法把它改回启用</b>，这个操作就变成不可逆的了。
     */
    List<CategoryVO> listAll();

    /**
     * 新增分类。
     *
     * @return 新分类的 id
     * @throws com.novel.common.exception.BusinessException 名称重复时
     *         {@code CATEGORY_NAME_EXISTS(6002)}
     */
    Long create(CategorySaveDTO dto);

    /**
     * 编辑分类（名称 / 排序 / 启停）。
     *
     * <p>★ 改名时要排除自己，否则「只改排序、名字不动」的请求
     * 会被自己的名字判成重复，用户看到「分类名称已存在」而莫名其妙。
     *
     * @throws com.novel.common.exception.BusinessException 分类不存在时
     *         {@code CATEGORY_NOT_FOUND(6001)}；改成别人的名字时
     *         {@code CATEGORY_NAME_EXISTS(6002)}
     */
    void update(Long id, CategorySaveDTO dto);

    /**
     * 删除分类。
     *
     * <p><b>★ 已被任何作品引用时拒绝删除</b>（{@code CATEGORY_HAS_NOVELS(6003)}），
     * 口径<b>不限作品状态</b> —— 草稿、已下架的书也是书，
     * 分类没了它们就成了孤儿（{@code category_id} 指向一个查不到的 id，
     * 前端显示分类名时是空白）。
     *
     * <p>想「下架一个分类但保留数据」应该用 {@code status = 0} 停用，
     * 不是删除 —— 停用不做引用检查，已有作品照常挂着。
     *
     * @throws com.novel.common.exception.BusinessException 分类不存在时
     *         {@code CATEGORY_NOT_FOUND(6001)}；名下还有作品时
     *         {@code CATEGORY_HAS_NOVELS(6003)}
     */
    void delete(Long id);
}
