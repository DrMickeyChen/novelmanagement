package com.novel.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novel.common.constant.CommonConstant;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.TokenStore;
import com.novel.common.security.UserContext;
import com.novel.modules.system.dto.RoleSaveDTO;
import com.novel.modules.system.entity.SysMenu;
import com.novel.modules.system.entity.SysRole;
import com.novel.modules.system.mapper.SysMenuMapper;
import com.novel.modules.system.mapper.SysRoleMapper;
import com.novel.modules.system.mapper.SysRoleMenuMapper;
import com.novel.modules.system.mapper.SysUserRoleMapper;
import com.novel.modules.system.service.AdminRoleService;
import com.novel.modules.system.vo.RoleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理端角色服务的实现。
 *
 * <p><b>★ S6 建的类，S13 补齐了增删改和赋菜单</b> —— 原来的 {@code list()}
 * 一行没动（这正是 S6 把它单独建出来的目的：用户管理页的编辑弹窗
 * 需要一份角色清单，不能等到 S13）。
 *
 * <p><b>★★ 这个类里最重要的不是 CRUD，是两条【防自锁】。</b>
 * 本项目的其他所有界面，操作错了最多是数据不对，改回来就是；
 * 而角色管理页有两处「UI 上的正常操作会造成不可逆损害」：
 * <ol>
 *   <li>删掉 / 停用内置角色 → 全局性故障，恢复要重新灌种子数据；</li>
 *   <li>去掉自己角色上的「角色管理 / 菜单管理」→ 界面上再也改不回来，
 *       恢复要进 MySQL 手工 {@code INSERT sys_role_menu}。</li>
 * </ol>
 * 两处的拦截各自只有几行判断，中招之后的成本是「连数据库」——
 * 这是本项目里性价比最高的一段代码。见 {@link #guardSelfLock}。
 *
 * <p><b>★ 这个类只碰 {@code sys_role} / {@code sys_role_menu} / {@code sys_menu}
 * / {@code sys_user_role} 四张表，全在 {@code modules/system} 自己的地盘里</b> ——
 * 没有跨模块查询，第二期 M1 摘 auth 服务时可以整个搬走，不需要任何改造。
 *
 * <p><b>★★ 角色变更【不进 {@code audit_log}】，这是有意的。</b>
 * {@code AuditTargetType} 的四个值是 NOVEL / CHAPTER / COMMENT / AUTHOR_APPLY ——
 * 它们都是<b>内容审核</b>的对象。角色是系统配置，混进去会让
 * 「审核日志」这一页的语义变成「什么都有」。
 * 真要记的话该另开一张「操作日志」表，那是 P5 明确不做的事
 * （见计划的「操作日志导出 → 按需」）。这里记的是服务端日志。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRoleServiceImpl implements AdminRoleService {

    /**
     * ★ 「把自己锁在门外」要守住的两个权限点。
     *
     * <p>为什么不放进 {@code CommonConstant}：这两个字符串是
     * <b>这一条防护策略</b>的参数，不是全项目通用的事实。
     * 它们必须和 {@code docs/sql/seed.sql} 里菜单 9 / 10 的 perms 一致 ——
     * 那是种子数据，改了那里就得改这里，唯一的保障是这两个字符串
     * 挨着写、并且有注释互相指路。
     *
     * <p>为什么是两个而不是一个：「角色管理」页是唯一能改回权限的地方，
     * 「菜单管理」页也依赖权限点才能显示。失去任何一个，管理员的
     * 视野里都会少一块再也补不回来的东西。
     */
    private static final List<String> SELF_LOCK_GUARD_PERMS =
            List.of("system:role:list", "system:menu:list");

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final TokenStore tokenStore;

    /* ==================== 查 ==================== */

    @Override
    public List<RoleVO> list() {
        /*
         * ★ 不分页、也不过滤 status。两个理由：
         *
         * ① 角色是个位数量级的字典表（种子里就 3 个），分页没有意义 ——
         *    下拉框和多选框要把它们全列出来。
         *
         * ② 【不能】过滤掉停用的角色。某个用户可能还挂着一个已停用的角色，
         *    编辑弹窗如果只列出启用的，那个角色会显示成「未勾选」——
         *    管理员一保存，这个关联就被悄悄删掉了，而他什么都没做。
         *    停用的角色要显示出来（界面上标灰、注明「已停用」），
         *    让管理员看见它、自己决定留不留。
         *
         * 排序按 id 升序。内置的三个角色是种子数据里最先插进去的
         * （{@code seed.sql} 按 id 1/2/3 灌入 <b>READER → AUTHOR → ADMIN</b>，
         * 注意【不是】按 ADMIN 打头的字母序 —— S6 写这段注释时想当然了，
         * S13 对过 seed 之后改正），于是内置角色总是排在最前面，
         * 管理员自己建的排在后面 —— 「重要的在上面」。
         * 用 role_code 排也行，但 id 更稳定（role_code 是可以改的）。
         */
        List<SysRole> roles = roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));

        /*
         * ★★ 用户数用【一次分组查】补齐，不是每个角色查一次。
         *
         * 后者是 N+1：8 个角色就是 9 次查询。角色数量少，页面照样秒开，
         * 所以这种写法不会被性能测试发现 —— 它只会随着角色变多慢慢变慢。
         * AdminUserServiceImpl#toVOList 上已经为同一类问题写过一大段注释，
         * 这里保持同一个做法。
         *
         * ★ 用一个 Map 在内存里 join，而不是在 SQL 里 LEFT JOIN sys_role ——
         *   因为 sys_role 是逻辑删除的表，join 时得自己写 deleted = 0；
         *   两次简单的 MP 查询（自动带 deleted = 0）比一条手写 SQL 更不容易错。
         */
        Map<Long, Long> countByRole = new HashMap<>();
        for (SysUserRoleMapper.RoleUserCount row : userRoleMapper.countGroupByRoleId()) {
            countByRole.put(row.getRoleId(), row.getUserCount());
        }

        return roles.stream()
                .map(role -> RoleVO.of(role, countByRole.get(role.getId())))
                .toList();
    }

    @Override
    public List<Long> menuIdsOf(Long roleId) {
        /*
         * ★ 先确认角色存在，而不是直接返回一个空列表。
         *
         * 角色不存在时返回 [] 的话，前端会渲染出一棵【一个都没勾的树】——
         * 看起来和「这个角色确实还没有任何权限」一模一样。
         * 管理员照着它勾一遍保存，得到的是 1011（菜单接口那边才会发现）,
         * 而他已经白勾了。1011 立刻告诉他「这个角色没了，刷新一下列表」。
         * 这也是「宁可报错也不要说一句不成立的话」那条老规矩。
         */
        requireRole(roleId);
        return roleMenuMapper.selectMenuIdsByRoleId(roleId);
    }

    /* ==================== 增 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(RoleSaveDTO dto) {
        String code = dto.getRoleCode().trim();
        requireCodeNotUsed(code, null);

        SysRole entity = new SysRole();
        entity.setRoleCode(code);
        entity.setRoleName(dto.getRoleName().trim());
        entity.setRemark(dto.getRemark());
        entity.setStatus(dto.getStatus());

        try {
            roleMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            /*
             * ★★ 这一处 catch 不是「重复校验」，它拦的是上面那个检查
             *    【查不到】的一种情况，很反直觉：
             *
             *    requireCodeNotUsed 走的是 SysRoleMapper#selectRoleIdByCode，
             *    那条 SQL 带 deleted = 0（是手写的，必须自己写）。
             *    而 uk_role_code 这个唯一索引【不含 deleted】——
             *    也就是说一个【被逻辑删除过】的角色，业务上「不存在」，
             *    但在索引里还占着那个码。
             *
             *    结果是：检查通过 → INSERT 撞键 → 不接这个异常的话它就是
             *    500「服务器开小差了」，而真实原因只是「这个码以前用过」。
             *
             *    这和 novel_category.uk_name / sys_user.uk_username 是同一个坑
             *    （见 SysRole 类注释最后一段），三个地方的处理方式一致。
             */
            log.warn("新增角色时撞唯一键（码已被逻辑删除的角色占用） | roleCode={}", code);
            throw new BusinessException(ResultCode.ROLE_CODE_EXISTS,
                    "角色标识「" + code + "」已被使用（可能是一个已删除的角色占用的，换一个码）");
        }

        log.info("新增角色 | id={} roleCode={} roleName={} status={}",
                entity.getId(), code, dto.getRoleName(), dto.getStatus());
        return entity.getId();
    }

    /* ==================== 改 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, RoleSaveDTO dto) {
        SysRole exist = requireRole(id);
        String code = dto.getRoleCode().trim();
        String name = dto.getRoleName().trim();

        /*
         * ★★ 内置角色的「身份证」不许改：标识和状态。理由见 ResultCode.ROLE_BUILTIN。
         *    名字和备注随便改 —— 那两样不影响任何一行代码的行为。
         *
         * ★ 注意判断用的是【库里读到的】roleCode（exist），不是 dto 里的：
         *   dto 里的是管理员想改成什么，exist 里的是这个角色【现在】是什么。
         *   要保护的是现在。
         */
        if (CommonConstant.BUILTIN_ROLE_CODES.contains(exist.getRoleCode())) {
            if (!exist.getRoleCode().equals(code)
                    || !CommonConstant.STATUS_ENABLED.equals(dto.getStatus())) {
                log.warn("拒绝修改内置角色的标识或状态 | id={} roleCode={} 传入={} status={}",
                        id, exist.getRoleCode(), code, dto.getStatus());
                throw new BusinessException(ResultCode.ROLE_BUILTIN,
                        "「" + exist.getRoleName() + "」是内置角色，只能改角色名和备注");
            }
        }

        requireCodeNotUsed(code, id);

        /*
         * ★ 只带要改的字段构造一个新对象，而不是「查出来的 exist 改几个字段再 updateById」。
         *   两种写法在这里结果一样，但第二种有隐患：exist 带着 createTime 等
         *   全部字段，将来谁在它上面多 set 一个字段（为了别处用），
         *   这个 update 就顺手写回库里了 —— 而改动在几行之外，review 时看不出来。
         *
         * ★★ remark 必须走 wrapper 的 .set()，这是本方法唯一一处「不能省」的写法。
         *    MP 的 updateById 默认跳过【null 字段】（FieldStrategy.NOT_NULL），
         *    所以「把备注清空」这个操作会【静默失效】—— 界面显示已保存，
         *    刷新后备注还在。这是 updateById 最经典的一个坑，
         *    NovelServiceImpl / ChapterServiceImpl 里为同类字段也是这么处理的。
         *
         * ★ 传了非 null 的实体，MybatisMetaObjectHandler 才会跑 updateFill
         *   （update_time 由应用填），传 null 的话那一步会被跳过。
         */
        SysRole update = new SysRole();
        update.setId(id);
        update.setRoleCode(code);
        update.setRoleName(name);
        update.setStatus(dto.getStatus());

        try {
            roleMapper.update(update, new LambdaUpdateWrapper<SysRole>()
                    .eq(SysRole::getId, id)
                    .set(SysRole::getRemark, dto.getRemark()));
        } catch (DuplicateKeyException e) {
            // 同 create：并发改名的兜底
            log.warn("编辑角色时撞唯一键（并发改码） | id={} roleCode={}", id, code);
            throw new BusinessException(ResultCode.ROLE_CODE_EXISTS, "角色标识「" + code + "」已存在");
        }

        /*
         * ★★ 改完必须把【这个角色下的所有用户】踢下线，但只在
         *    「标识或状态真的变了」的时候踢 —— 改个名字就踢人下线是没有道理的。
         *
         * 为什么这两样变了就必须踢：角色和权限是【登录时】算出来写进 Redis 的
         * （AuthServiceImpl），之后每请求只读 Redis、不查库。
         *   - status 改成 0 → selectRoleCodesByUserId 带 status = 1，
         *     这个人下次登录就没有这个角色了。不踢的话他还能拿着旧身份用到 token 过期。
         *   - roleCode 改了 → 所有 hasRole("XXX") 的判断结果都变了，
         *     而 Redis 里存的还是旧的角色码。
         * 不踢的症状是「对方界面看起来毫无变化」，管理员以为没保存成功，然后再改一次。
         */
        if (!exist.getRoleCode().equals(code)
                || !exist.getStatus().equals(dto.getStatus())) {
            int kicked = kickUsersOf(id);
            log.info("编辑角色 | id={} roleCode={}->{} status={}->{} 已强制 {} 名用户重新登录",
                    id, exist.getRoleCode(), code, exist.getStatus(), dto.getStatus(), kicked);
        } else {
            log.info("编辑角色 | id={} roleCode={}（标识与状态未变，不动在线会话）", id, code);
        }
    }

    /* ==================== 删 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole exist = requireRole(id);

        if (CommonConstant.BUILTIN_ROLE_CODES.contains(exist.getRoleCode())) {
            log.warn("拒绝删除内置角色 | id={} roleCode={}", id, exist.getRoleCode());
            throw new BusinessException(ResultCode.ROLE_BUILTIN,
                    "「" + exist.getRoleName() + "」是内置角色，不能删除");
        }

        /*
         * ★★ 角色下还有用户就【拒绝删除】，而不是级联删掉那些 sys_user_role 行。
         *
         * 级联删看起来更「方便」，实际是把一次误操作放大成一次事故：
         * 那些用户会变成「登录成功但什么都进不去」（roles 是空数组），
         * 而这个状态在界面上完全看不出原因 —— 见 AdminUserServiceImpl 里
         * 「悬空关联」那段注释。删一个角色不该顺手废掉一批人。
         *
         * ★ 和 sys_role_menu 的处理【正好相反】（那边是级联物理清理）：
         *   菜单关联没了无非是权限少了，而用户的角色没了是「这个人被废掉」。
         *   后果不同，处理方式就该不同。
         *
         * ★ 用 selectUserIdsByRoleId 而不是 selectCount：错误提示里要带上人数
         *   （「还有 3 个用户」比「还有用户」可操作得多），而且不用多写一个方法。
         *   角色下的用户数是个位到百位级，一次 IN 查询没问题。
         */
        List<Long> users = userRoleMapper.selectUserIdsByRoleId(id);
        if (!users.isEmpty()) {
            log.warn("拒绝删除还有用户的角色 | id={} roleCode={} 用户数={}",
                    id, exist.getRoleCode(), users.size());
            throw new BusinessException(ResultCode.ROLE_IN_USE,
                    "该角色下还有 " + users.size() + " 个用户，请先把他们改到别的角色再删除");
        }

        /*
         * 逻辑删除（@TableLogic 会自动把 deleteById 变成 UPDATE ... SET deleted = 1）。
         * ★ 不踢人：上面刚保证了这是死人角色，没有任何在线会话挂在它上面。
         */
        roleMapper.deleteById(id);

        /*
         * ★★ 级联清掉这个角色的菜单关联 —— 这里是【物理 DELETE】，而且必须如此。
         *    sys_role_menu 没有 deleted 列（schema.sql），加 @TableLogic 会直接
         *    Unknown column 报错。见 SysRoleMenuMapper#deleteByRoleId。
         *
         * ★ 这一步其实【不影响任何鉴权】：角色被逻辑删除后，
         *   selectPermsByUserId 那条 SQL 里 r.deleted = 0 会 join 不上，
         *   这些行本来就已经不产生权限了。清掉是为了不留垃圾行 ——
         *   否则「某个 menu_id 到底是谁授的」这个问题永远查不清。
         */
        int removed = roleMenuMapper.deleteByRoleId(id);

        log.info("删除角色 | id={} roleCode={} 同时清理菜单关联={} 条", id, exist.getRoleCode(), removed);
    }

    /* ==================== 赋菜单 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int assignMenus(Long roleId, List<Long> menuIds) {
        SysRole role = requireRole(roleId);

        /*
         * ★ 去重：前端传 [2, 2] 完全可能（el-tree 在父子联动下偶尔会给出重复项），
         *   不去重的话会撞 uk_role_menu 唯一键 → DuplicateKeyException → 500。
         *   用户的意图是明确的，没必要因此报错。
         *   （和 AdminUserServiceImpl#assignRoles 里那处同理。）
         *
         * ★ menuIds 允许为 null / 空（表示这个角色暂时没有任何菜单），
         *   所以这里用 List.of() 兜底而不是抛异常 —— 理由见 RoleMenuDTO 的类注释。
         */
        List<Long> distinctMenuIds = menuIds == null
                ? List.of()
                : menuIds.stream().distinct().toList();

        /*
         * ★★ 三个校验全部放在【任何写操作之前】。
         *
         *    写错了再靠 @Transactional 回滚当然也能恢复，但那是「先做错事、
         *    再撤销」——白白依赖回滚，而且一旦将来有人把这几个校验拆到别处
         *    （或者事务传播改错），就会真的写进去。
         *    AdminUserServiceImpl#assignRoles 里对自锁检查也是这个顺序。
         */
        requireMenusExist(distinctMenuIds);
        guardSelfLock(roleId, distinctMenuIds);

        /*
         * ★★ 覆盖式赋值：先物理全删、再批量插入，两件事在同一个事务里。
         *    sys_role_menu 没有 deleted 列，「关联不存在 = 行不存在」，
         *    所以这里是物理 DELETE —— 见 SysRoleMenuMapper#deleteByRoleId。
         *
         * ★ 为空时【不调用 insertBatch】：空集合会让 foreach 生成
         *   「VALUES 」这种语法错误。语义上也讲不通 —— 一个菜单都不给，
         *   正确做法就是什么都不插。
         */
        roleMenuMapper.deleteByRoleId(roleId);
        if (!distinctMenuIds.isEmpty()) {
            roleMenuMapper.insertBatch(roleId, distinctMenuIds);
        }

        /*
         * ★★ 改完菜单必须踢人，否则这个功能等于没做。
         *    权限点是在登录时算出来写进 Redis 的，不踢的话被改的人
         *    界面上毫无变化，管理员会以为没保存成功。
         *    （和 AdminUserServiceImpl#assignRoles 末段同一条理由。）
         */
        int kicked = kickUsersOf(roleId);

        log.info("角色赋菜单 | id={} roleCode={} 菜单数={} 已强制 {} 名用户重新登录",
                roleId, role.getRoleCode(), distinctMenuIds.size(), kicked);
        return kicked;
    }

    /* ==================== 防自锁 ==================== */

    /**
     * 拦住「管理员把自己锁在门外」—— <b>本项目里唯一一处 UI 操作能造成不可逆损害的地方</b>。
     *
     * <p><b>场景：</b>管理员编辑自己所属的角色，取消勾选「角色管理 / 菜单管理」，
     * 点保存。如果放行，会发生三件事，每一件都不可逆：
     * <pre>
     *   ① 保存成功 → sys_role_menu 里那两行没了
     *   ② 紧接着的 removeAll 把管理员【自己】踢下线
     *   ③ 重新登录后，左侧菜单里这两项消失（菜单是按权限点过滤的）
     *      → 界面上再也没有地方能把它加回来 → 只能进 MySQL 手工 INSERT
     * </pre>
     *
     * <p><b>★★ 为什么不能简单地「判断目标角色是不是我的角色就拦」：</b>
     * 一个人可以同时拥有多个角色（ADMIN + 自定义角色）。如果他有 ADMIN，
     * 那么把自定义角色上的 {@code system:role:list} 去掉<b>并不会</b>让他失去它 ——
     * ADMIN 那边还授着。那种情况下拦截就是误伤：管理员会看到一句
     * 「你将无法再改回来」，而他明明改得回来。
     * <b>所以真正要判断的是「改完之后，我自己还剩哪些权限」，
     * 而不是「我改的是不是我自己的角色」。</b>
     *
     * <p><b>★★ 两侧的过滤条件必须完全一致，这是本方法最容易写错的地方。</b>
     * 右边（别人的角色）走的是 {@code SysRoleMenuMapper#selectPermsByRoleIds}，
     * 它带 {@code m.deleted = 0 AND m.status = 1}；左边（这个角色的新菜单）
     * 也必须带同样的条件。少一个 {@code status = 1} 的后果是：
     * 一个【已停用】的菜单被算进「我还有这个权限」，守卫放行，
     * 而登录时 {@code selectPermsByUserId}（带 status = 1）算出来的权限里没有它 ——
     * <b>人被锁死了，守卫却说没事。</b>
     *
     * @param roleId      正在被改的角色
     * @param newMenuIds  这次要给它勾上的菜单（已去重）
     */
    private void guardSelfLock(Long roleId, List<Long> newMenuIds) {
        Long me = UserContext.getUserId();
        if (me == null) {
            /*
             * ★ 理论上到不了这里：/api/admin/** 在 SecurityConfig 里要求 ADMIN，
             *   没有登录身份根本进不来。留着这个分支是兜底 ——
             *   UserContext 取不到身份时应该【放行】还是【拒绝】？
             *   这里选放行：守卫的存在是为了保护「某个具体的管理员」，
             *   没有具体的人可保护时，它不该变成一个谁都过不去的关卡。
             *   （真正该拦住匿名请求的是安全链，不是这里。）
             */
            log.warn("赋菜单时取不到当前用户身份，跳过自锁检查 | roleId={}", roleId);
            return;
        }

        List<Long> myRoleIds = userRoleMapper.selectRoleIdsByUserId(me);
        if (!myRoleIds.contains(roleId)) {
            // 改的不是自己的角色，与我无关
            return;
        }

        /*
         * 「改完之后我还能拿到哪些权限」= 我【别的】角色的权限 ∪ 这个角色的新权限。
         * ★ 别的角色为空时不能调 selectPermsByRoleIds（IN () 是语法错误），
         *   所以那个分支直接跳过 —— 见那个方法的注释。
         */
        List<Long> otherRoleIds = myRoleIds.stream()
                .filter(id -> !id.equals(roleId))
                .toList();

        Set<String> permsAfter = new HashSet<>();
        if (!otherRoleIds.isEmpty()) {
            permsAfter.addAll(roleMenuMapper.selectPermsByRoleIds(otherRoleIds));
        }
        permsAfter.addAll(permsOfMenus(newMenuIds));

        List<String> missing = SELF_LOCK_GUARD_PERMS.stream()
                .filter(perm -> !permsAfter.contains(perm))
                .toList();
        if (!missing.isEmpty()) {
            log.warn("拒绝自锁：改完后当前管理员将失去 {} | userId={} roleId={} 新菜单={}",
                    missing, me, roleId, newMenuIds);
            throw new BusinessException(ResultCode.ROLE_SELF_MENU_LOCK,
                    "保存后你自己将失去 " + missing + "，界面上再也改不回来");
        }
    }

    /**
     * 取一批菜单的权限点。
     *
     * <p><b>★ 必须和 {@code SysUserRoleMapper#selectPermsByUserId} 用同一套过滤条件</b>
     * （{@code deleted = 0}、{@code status = 1}、perms 非空），
     * 因为这里算的是「登录之后我会拿到什么权限」—— 那正是那条 SQL 算的东西。
     * 两边不一致，守卫就会放行一个真正会锁死人的改动。理由详见 {@link #guardSelfLock}。
     *
     * <p>★ {@code deleted = 0} 由 {@code @TableLogic} 自动带上（这是 MP 的方法，
     * 不是手写 SQL）—— 又一次「能不手写就不手写」。
     *
     * <p><b>★★ 这里【故意不写】{@code .select(SysMenu::getPerms)} —— 别再优化回去。</b>
     * 写了会让 {@code PUT /roles/{id}/menus} 直接 500（S13 实测踩过：NPE 落在下面
     * 那行 {@code .map(SysMenu::getPerms)} 上）。原因不在 MyBatis-Plus，在 MyBatis：
     * <ol>
     *   <li>只投影一列时，一行里「被映射的列」就只有这一列；</li>
     *   <li>{@code sys_menu} 的<b>目录行 perms 本来就是 NULL</b>
     *       （见 {@code SysMenu#perms} 那句「目录行为 null」）——</li>
     *   <li>MyBatis 的 {@code returnInstanceForEmptyRow} 默认是 {@code false}，
     *       一行的映射列全为 NULL 时它<b>把整行丢掉、往 List 里塞一个 {@code null}</b>
     *       （{@code DefaultResultSetHandler#getRowValue} 的最后一行）；</li>
     *   <li>于是 {@code List<SysMenu>} 里躺着几个 {@code null}，
     *       方法引用一解引用就 NPE。</li>
     * </ol>
     * 三个条件缺一不可：<b>投影成单列</b> + <b>该列可为 NULL</b> + <b>真出现了 NULL</b>。
     *
     * <p>★ 正因如此，{@code ContentUserLookup} 里那句 {@code .select(SysUser::getId)}
     * 是安全的 —— 主键永远不为 NULL。<b>但那是「碰巧安全」，不是「有保证」</b>，
     * 别照着它把单列投影抄到可空列上。
     *
     * <p>不投影的代价是每行多读几列。这张表 15 行，代价约等于零；换来的是
     * 「不会因为某个菜单的 perms 为空就 500」。真要省这几列，唯一正确的写法是
     * {@code selectObjs}（它老实返回 {@code List<Object>}）并自己判空 ——
     * 而不是继续假装它是 {@code List<SysMenu>}。
     */
    private Set<String> permsOfMenus(List<Long> menuIds) {
        if (menuIds.isEmpty()) {
            return Set.of();
        }
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                        .in(SysMenu::getId, menuIds)
                        .eq(SysMenu::getStatus, CommonConstant.STATUS_ENABLED))
                .stream()
                .map(SysMenu::getPerms)
                .filter(perm -> perm != null && !perm.isBlank())
                .collect(Collectors.toSet());
    }

    /* ==================== 私有工具 ==================== */

    /**
     * 把这个角色下的所有用户踢下线。
     *
     * <p><b>★ 返回的是【用户数】，不是登录态条数。</b>
     * {@code tokenStore.removeAll} 返回的是它删掉的 key 个数 ——
     * 一个人在三台设备上登录就是 3。而界面上要说的是
     * 「N 名用户需要重新登录」，所以这里数的是「有多少人的会话真的被清了」。
     *
     * <p><b>★ 为什么是循环而不是一条命令：</b>{@code removeAll} 内部是
     * {@code SCAN 匹配 AUTH_TOKEN:userId:*}，模式里带 userId，
     * 所以没法一次扫多个用户（写 {@code *} 会把所有人的会话都删掉）。
     * 一个角色下的用户数是可控的（内置角色里 ADMIN 就一两个，
     * 自定义角色通常是个位数），而「改角色」是低频管理操作，
     * 这个循环完全够用。
     * <p><b>★ 真到量大了怎么办：</b>把「用户 → token」也存一份索引
     * （比如 {@code novel:auth:user-tokens:{userId}} 这个 Set），
     * 就可以按角色批量收集 key 再一次性删。现在不做 ——
     * 为一个低频管理操作多维护一份需要保证一致性的索引，不划算。
     */
    private int kickUsersOf(Long roleId) {
        List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
        int kickedUsers = 0;
        for (Long userId : userIds) {
            if (tokenStore.removeAll(userId) > 0) {
                kickedUsers++;
            }
        }
        return kickedUsers;
    }

    /**
     * 取角色，不存在就抛 {@code ROLE_NOT_FOUND(1011)}。
     *
     * <p>{@code selectById} 自动带 {@code deleted = 0}（{@code @TableLogic}），
     * 所以「已删除的角色」在这里等同于「不存在」—— 这正是我们要的。
     */
    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ResultCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    /**
     * 校验角色标识没被占用。
     *
     * <p><b>★ 这是「尽力而为」的友好提示，真正拦住重名的是
     * {@code uk_role_code} 唯一键</b> —— 调用方必须同时接住
     * {@code DuplicateKeyException}。两个理由：
     * <ol>
     *   <li>并发：两个人同时建同名角色，各自检查时对方都还没提交；</li>
     *   <li><b>更常见的那个</b>：{@code selectRoleIdByCode} 带 {@code deleted = 0}，
     *       而唯一索引不含 {@code deleted} —— 一个已逻辑删除的角色
     *       在这里查不到，在索引里却还占着坑。</li>
     * </ol>
     * 这套「检查 + catch 兜底」的写法在 {@code CategoryServiceImpl} /
     * {@code AuthServiceImpl} 里都是一样的。
     *
     * @param excludeId 编辑时要排除自己，否则「只改备注、码不动」会被自己判成重名
     */
    private void requireCodeNotUsed(String roleCode, Long excludeId) {
        Long ownerId = roleMapper.selectRoleIdByCode(roleCode);
        if (ownerId != null && !ownerId.equals(excludeId)) {
            throw new BusinessException(ResultCode.ROLE_CODE_EXISTS,
                    "角色标识「" + roleCode + "」已存在");
        }
    }

    /**
     * 校验这批菜单 id 都真实存在。
     *
     * <p><b>★ 不校验的后果是「悬空关联」</b>：{@code sys_role_menu} 里会多出一行
     * 指向不存在菜单的记录。它<b>不影响任何鉴权</b>（权限查询 join 不到菜单
     * 就自动忽略了），所以不报错、不会被发现 —— 只会在
     * 「这个角色到底被授了什么」这个问题上永远给出一个假的答案。
     *
     * <p><b>★ 允许勾选【已停用】的菜单</b>（status = 0）：停用是
     * 「暂时不生效」，不是「这个菜单不存在」—— 和 {@code SysRoleMapper}
     * 里「停用的角色仍然可以被授予」是同一条理由。
     * 所以这里只查 {@code deleted}（由 {@code @TableLogic} 自动带上），
     * 不额外加 {@code status = 1}。
     */
    private void requireMenusExist(List<Long> menuIds) {
        if (menuIds.isEmpty()) {
            return;
        }
        Long found = menuMapper.selectCount(
                new LambdaQueryWrapper<SysMenu>().in(SysMenu::getId, menuIds));
        if (found == null || found != menuIds.size()) {
            log.warn("赋菜单失败：菜单不存在 | 传入={} 查到={}", menuIds, found);
            throw new BusinessException(ResultCode.MENU_NOT_FOUND);
        }
    }
}
