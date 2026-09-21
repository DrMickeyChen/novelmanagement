/**
 * 审核日志接口 —— 对应后端 AdminAuditLogController（/api/admin/logs）。
 *
 * ------------------------------------------------------------
 * ★★ 这是**只读**的一张表，而且以后也不会变成可写的
 * ------------------------------------------------------------
 * 后端只有 GET，没有 POST / PUT / DELETE —— 不是「还没做」，是「不该做」：
 * 流水是**只追加**的，能改的日志不能用来追责。
 * 所以这个文件里只有 `getAuditLogPage` 一个函数，**不要"顺手"补一个删除**。
 *
 * ★ 同理，前端这一页也【没有】任何「清理日志」的入口。
 *
 * ------------------------------------------------------------
 * ★★ 两个筛选参数是**数字**，不是枚举名
 * ------------------------------------------------------------
 * 后端 Controller 里写的是 `AuditTargetType.fromCode(targetType)` —— 数字进、
 * 枚举出。传 `?action=SUBMIT` 会落到 Spring 默认的按名转换上，而同一个值的
 * JSON 表示却是数字。**一个字段两种写法，迟早有人写错一边。**
 * 统一用数字，下拉框的 value 也就是数字，不用做任何映射。
 */
import request from '@/utils/request'

/**
 * 审核日志分页。
 *
 * ★ 排序是「自增 id 倒序」，等价于时间倒序。用 id 而不是 create_time 是因为
 *   `create_time` 只精确到秒 —— 同一秒内的两条记录用时间排是**不稳定**的，
 *   翻页时会看到某一条重复出现、另一条永远看不到。
 *
 * ★★ `targetTitle` 为 null 是**正常情况**，不是数据坏了：
 *   那个被审核的对象后来被逻辑删除了，`AuditLogServiceImpl` 就 join 不出标题。
 *   前端必须显示成「作品 #5（已删除）」而不是空白 —— 空白会让人以为
 *   这条日志本身是坏的，而它其实是最有价值的一类记录
 *   （「这本书被谁审过、然后被谁删了」）。
 *
 * @param {object} params
 * @param {number} [params.targetType] 1作品 2章节 3评论 4作者申请。不传=全部
 * @param {number} [params.action] 1提交 2通过 3驳回 4下架 5重新上架 6退回草稿。不传=全部
 * @param {number} [params.operatorId] 操作人用户 id。不传=全部
 * @param {number} [params.page]
 * @param {number} [params.size] 上限 100（后端 PaginationInnerInterceptor 夹的）
 * @returns {Promise<{records: Array, total: number}>}
 */
export function getAuditLogPage(params) {
  return request.get('/admin/logs', { params })
}
