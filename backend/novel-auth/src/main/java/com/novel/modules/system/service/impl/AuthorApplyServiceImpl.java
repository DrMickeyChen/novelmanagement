package com.novel.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.auth.mq.producer.AuthorApplyAuditProducer;
import com.novel.common.constant.CommonConstant;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditStatus;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.PageResult;
import com.novel.common.result.ResultCode;
import com.novel.common.security.TokenStore;
import com.novel.common.security.UserContext;
import com.novel.modules.system.dto.AuthorApplyAuditDTO;
import com.novel.modules.system.dto.AuthorApplyCreateDTO;
import com.novel.modules.system.entity.AuthorApply;
import com.novel.modules.system.entity.SysUser;
import com.novel.modules.system.mapper.AuthorApplyMapper;
import com.novel.modules.system.mapper.SysRoleMapper;
import com.novel.modules.system.mapper.SysUserMapper;
import com.novel.modules.system.mapper.SysUserRoleMapper;
import com.novel.modules.system.service.AuthorApplyService;
import com.novel.modules.system.vo.AuthorApplyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者申请的实现。
 *
 * <p><b>★★ 这个类只碰 {@code author_apply} / {@code sys_user} / {@code sys_role} /
 * {@code sys_user_role} 四张表，全都在 {@code modules/system} 自己的地盘里</b> ——
 * 没有一处跨模块查询，M1 摘 auth 服务时可以整个搬走。
 *
 * <p><b>★★★ M3 之后，这里连「唯一的例外」也没有了。</b>
 * M1/M2 时期这句话后面跟着一句「唯一的例外是记审核流水，那件事在 content」——
 * 那时它走的是 {@code AuthorApplyAuditRecorder} 这个<b>定义在本模块</b>的接口
 * （实现在 content 侧，靠一次 Feign 调用）。M3 把那一步换成了
 * {@link AuthorApplyAuditProducer} 的一条<b>事务消息</b>，
 * 于是 {@code novel-auth} 里<b>一条出站边都不剩</b>：
 * 那个接口、它的实现、以及 {@code ContentAuditClient} 全部删掉了，
 * {@code @EnableFeignClients} 也一并摘掉了（见 {@code AuthApplication}）。
 *
 * <p>★ <b>注意「代码依赖」和「运行时依赖」这次是同时消失的</b>，这是 M1 那次没有的：
 * M1 只是把依赖拧了个方向（接口留在消费方，实现变成发一次 HTTP），
 * 所以{@code novel-auth ⇄ novel-content} 那个<b>服务级的环</b>还在；
 * M3 把「发」这件事本身交给了 Broker，环才真正断开。
 * <b>别再说这个类是「跨模块接缝的范例」了</b> —— 它现在是「没有接缝」的范例。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorApplyServiceImpl implements AuthorApplyService {

    /**
     * 按申请人（用户名 / 昵称）搜索时的条数上限。
     *
     * <p>★ 和 {@code ContentUserLookup#NICKNAME_MATCH_LIMIT} 是同一个理由：
     * {@code sys_user.nickname} 没有索引，而且前导通配符 {@code LIKE '%kw%'}
     * 让任何索引都用不上，这是一次全表扫描。
     * 不设上限的话，搜到一个 100+ 人重名的昵称会带出几万个 id 去做 {@code IN} ——
     * 那是一次真正的故障（SQL 文本超长、解析开销大），而不是「慢一点」。
     * <p>代价是重名时结果被截断、可能漏掉几份申请，这是<b>可以接受的降级</b>。
     */
    private static final int APPLICANT_MATCH_LIMIT = 100;

    private final AuthorApplyMapper applyMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final TokenStore tokenStore;

    /**
     * ★ M3：从「一次 Feign 写」换成了「一条事务消息」。
     *
     * <p>类型从 {@code AuthorApplyAuditRecorder} 变成本类，是 M3 唯一动到本类的地方 ——
     * <b>业务逻辑一行没改</b>，连调用点的位置都没挪（见 {@code audit()} 的注释）。
     * 这个生产者住在 {@code com.novel.auth.mq}，是 {@code com.novel.auth} 下
     * <b>唯一一个跟外部世界打交道的包</b>，而它打交道的对象是 Broker、不是兄弟服务。
     */
    private final AuthorApplyAuditProducer auditProducer;

    /* ================================================================
     *  管理端
     * ================================================================ */

    @Override
    public PageResult<AuthorApplyVO> adminPage(AuditStatus status, String keyword,
                                               long page, long size) {
        LambdaQueryWrapper<AuthorApply> wrapper = new LambdaQueryWrapper<AuthorApply>()
                .eq(status != null, AuthorApply::getStatus, status);

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();

            /*
             * ★ 申请人（用户名 / 昵称）在另一张表里，所以先查出 id 集合再 IN。
             *
             * 为什么不 join：本 Mapper 刻意一条手写 SQL 都没有（见 AuthorApplyMapper），
             * 而手写 join 必须自己写两个 deleted = 0（author_apply 和 sys_user 各一个）
             * —— 漏掉任何一个都不报错，只会让已注销用户的申请混进列表。
             * 这里两次查询都走 Wrapper，@TableLogic 自动生效。
             *
             * ★ .select(SysUser::getId) 只取 id 一列：不把 password（BCrypt 哈希）
             *   捞进内存 —— 泄露哈希等于把离线爆破的门槛降到「拖库」以下。
             *   和 ContentUserLookup#userIdsByNickname 同一个做法。
             *
             * ★ 单列投影在这里【安全】：主键永远不为 NULL，MyBatis 不会把整行丢成
             *   null 塞进 List。这个「碰巧安全」的边界见
             *   AdminRoleServiceImpl#permsOfMenus —— 那里选了可空列，500 了。
             */
            List<Long> applicantIds = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .select(SysUser::getId)
                            .and(w -> w.like(SysUser::getUsername, kw)
                                    .or()
                                    .like(SysUser::getNickname, kw))
                            .last("LIMIT " + APPLICANT_MATCH_LIMIT))
                    .stream().map(SysUser::getId).toList();

            /*
             * ★★ 这个 and(...) 包一层是必须的，不是风格问题。
             *
             * 不包的话拼出来的是：
             *   WHERE status = 0 AND pen_name LIKE '%x%' OR user_id IN (...)
             * SQL 里 AND 的优先级高于 OR，实际等于：
             *   WHERE (status = 0 AND pen_name LIKE '%x%') OR (user_id IN (...))
             * 于是【筛选「待审」时会漏出这些人的全部申请，包括已通过和已驳回的】——
             * 而结果看起来「也挺像那么回事」，很难发现。
             * （AdminUserServiceImpl 的关键词筛选踩的是同一个坑，注释在那边。）
             */
            wrapper.and(w -> {
                w.like(AuthorApply::getPenName, kw);
                /*
                 * ★ if 必须在里面，而且不能省：用户一个都没匹配上时
                 *   直接 .or().in(...) 会生成 `OR user_id IN ()`，
                 *   那是 SQL 语法错误 —— 注意它【不是】返回空列表，是直接抛异常。
                 *   而「笔名匹配、申请人昵称一个都没匹配上」是完全正常的输入。
                 */
                if (!applicantIds.isEmpty()) {
                    w.or().in(AuthorApply::getUserId, applicantIds);
                }
            });
        }

        /*
         * ★★ 先按状态升序（0待审 → 1已通过 → 2已驳回），再按 id 倒序。
         *
         * 这是一个【待办队列】页面，不是档案页 —— 管理员打开它 99% 是为了
         * 处理待审的那几份。按 id 倒序排的话，待审的申请会被夹在一堆
         * 已处理的中间，每来一份新的就要往下翻一次。
         * 同状态内按 id 倒序 = 最新提交的在最前面（id 自增，见 SysUserMapper 同款理由：
         * 用 create_time 排，同一秒提交的两份顺序不稳定，翻页会「自己跳」）。
         *
         * ★ 按状态筛选时这一句是无害的（该列恒等），不用为它加 if。
         */
        wrapper.orderByAsc(AuthorApply::getStatus)
                .orderByDesc(AuthorApply::getId);

        Page<AuthorApply> result = applyMapper.selectPage(buildPage(page, size), wrapper);
        return new PageResult<>(toVOList(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    /**
     * 实体列表 → VO 列表，<b>顺带批量补齐申请人的用户名和昵称</b>。
     *
     * <p><b>★★ 一次 {@code IN} 查完，不是逐条查。</b>
     * 逐条查申请人就是经典的 N+1：一页 20 条变成 21 次查询。
     * 而它<b>结果完全正确</b>，功能测试里永远发现不了，
     * 只会在用户量上来之后表现为「作者申请页越来越慢」。
     * （{@code AdminUserServiceImpl#toVOList} 里有一段一模一样的说明。）
     *
     * <p><b>★ 查不到的申请人（已注销）返回 null，不是异常。</b>
     * {@code selectByIds} 带 {@code @TableLogic}，已注销的用户查不出来，
     * 于是 {@code username} / {@code nickname} 为空，前端显示「已注销」——
     * 和 {@code CommentVO#nickname} 的处理完全一致。
     * <b>申请本身照常显示</b>：审核员要处理的正是这些历史遗留的申请，
     * 为了补一个用户名而绕过逻辑删除是错误的修法。
     */
    private List<AuthorApplyVO> toVOList(List<AuthorApply> records) {
        if (records.isEmpty()) {
            /*
             * ★ 提前返回不只是省一次查询 —— 更重要的是
             *   `IN ()` 空集合在 SQL 里是【语法错误】。
             */
            return List.of();
        }

        List<Long> userIds = records.stream().map(AuthorApply::getUserId).distinct().toList();

        Map<Long, SysUser> userById = new LinkedHashMap<>();
        for (SysUser user : userMapper.selectByIds(userIds)) {
            userById.put(user.getId(), user);
        }

        return records.stream().map(a -> {
            SysUser applicant = userById.get(a.getUserId());
            return AuthorApplyVO.forAdmin(a,
                    applicant == null ? null : applicant.getUsername(),
                    applicant == null ? null : applicant.getNickname());
        }).toList();
    }

    /**
     * 审核：通过（并授予 AUTHOR 角色）或驳回。
     *
     * <p><b>★ 事务：申请状态、{@code sys_user_role}、审核流水三者必须一起成功或一起失败。</b>
     *
     * <p><b>★★ 这句「一起成功或一起失败」在 M2 期间是【假的】，M3 才把它做成真的 ——
     * 这段沿革必须留着，它是理解本方法为什么要用事务消息的全部背景。</b>
     * <pre>
     *   改造前（单体）  流水和审核在同一个进程、同一个事务里，三者确实同生共死。
     *   M2（拆服务）    「写流水」变成了一次 Feign 调用，而【Feign 不参与调用方的事务】。
     *                  于是 content 那边立刻提交了一条流水，auth 这边随后一回滚，
     *                  它就成了一条不会有人删的【孤儿流水】——
     *                  而那句注释还挂在这里，说的是「一起成功或一起失败」。
     *   M3（本次）      换成一条【事务消息】：半消息先行、本地事务提交后才可见、
     *                  本进程崩了由 Broker 回查裁决。三个东西重新变成一个原子事实。
     * </pre>
     * ★ 所以 {@code publishAuthorApplyAudit} 的位置<b>刻意保持在最后一行</b>：
     * 不是因为它必须在最后（事务消息已经不要求这个了 —— 半消息的可见性由
     * afterCommit 决定，和它在方法体的哪一行无关），而是因为<b>把它挪走没有任何好处，
     * 而留着它能让这次改动的 diff 只有一行</b>。
     *
     * <p>★ 反过来说：如果 {@code grantAuthorRole} 失败又没回滚，
     * 申请人会拿到「已通过」但进不去创作中心 —— 见类注释。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long applyId, AuthorApplyAuditDTO dto) {
        Long operatorId = UserContext.requireUserId();

        AuthorApply apply = applyMapper.selectById(applyId);
        if (apply == null) {
            throw new BusinessException(ResultCode.AUTHOR_APPLY_NOT_FOUND);
        }

        AuditStatus target = Boolean.TRUE.equals(dto.getPass())
                ? AuditStatus.APPROVED
                : AuditStatus.REJECTED;

        /*
         * ★★ 幂等：已经是目标状态 → 直接返回成功，什么都不做。
         *
         * 「同一份申请批两次」在真实使用里就是【连点两下】，用户意图和第一次
         * 完全相同，没有理由报错。这里和评论审核（重复提交返回 4005）
         * 刻意不一致，两者的差别是真实的：
         *   评论 —— 有「改判」（隐藏 ↔ 显示），一个已显示的评论收到「通过」时，
         *           管理员真正该知道的是「你看到的不是最新状态」，所以要报错；
         *           而且重复执行会多记一条 audit_log，污染「处置了几条违规」的统计。
         *   申请 —— 终态是单向的，重复点同一个按钮没有任何歧义。
         * ★ 但幂等【只对同一个目标状态】成立：见下面那条判断。
         */
        if (apply.getStatus() == target) {
            log.info("作者申请审核：已是目标状态，视为重复提交 | applyId={} status={} operatorId={}",
                    applyId, target, operatorId);
            return;
        }

        /*
         * ★★ 终态之间不能互改：驳回过的不能追认通过。
         *
         * 这条不是「状态机洁癖」，而是两个具体后果：
         *   ① 一个用户被驳回之后【又提交了一份】（这是允许的）——
         *      如果第一份还能改成「通过」，系统里就会出现两份「已通过」的申请，
         *      而它们是同一个人的同一件事；
         *   ② 审核流水里的时间顺序会变得无法解释：先记 REJECT、后记 APPROVE，
         *      中间隔了三天，看不出是因为什么翻的案。
         * 要重来就让他重新提交，那会是一条新的、干净的记录。
         *
         * ★ 走到这里 status 只可能是 APPROVED / REJECTED（PENDING 已被上面
         *   两个分支排除），所以不需要再判一次 PENDING。
         */
        if (apply.getStatus() != AuditStatus.PENDING) {
            log.warn("作者申请审核：状态不允许该流转 | applyId={} 当前={} 目标={} operatorId={}",
                    applyId, apply.getStatus(), target, operatorId);
            throw new BusinessException(ResultCode.AUTHOR_APPLY_STATUS_ILLEGAL,
                    "「" + apply.getStatus().getDesc() + "」的申请不能改成「" + target.getDesc()
                            + "」，要重新申请请让用户再提交一份");
        }

        LocalDateTime now = LocalDateTime.now();
        /*
         * ★★ 一次乐观检查：UPDATE 上挂 eq(status, 读到的状态)。
         *
         *   两个管理员同时审同一份（或者一个人两个标签页），都会出现
         *   「两边都读到 PENDING、都过了上面那两条判断」的情况。
         *   带上这个条件之后，后到的那条命中 0 行，于是走到下面的分支返回 1016 ——
         *   而不是默默地再写一遍、再发一条 AUTHOR 角色、再记一条 audit_log。
         *
         * ★ author_apply 表【没有 version 列】（MP 的乐观锁插件靠 @Version 字段工作），
         *   所以用「前置状态」当版本号。Novel / Chapter / NovelComment 用的也是这个办法。
         *
         * ★★ audit_remark 必须走 wrapper 的 set()，不能只靠实体：
         *   MP 的 update 默认只更新【非 null】字段，而「通过」时 remark 常常是 null。
         *   不过这一处写 wrapper 的真正理由是【对称】：无论通过还是驳回都显式写这一列，
         *   读代码的人不用去记「哪种情况走哪条路」。见 NovelServiceImpl#transitStatus
         *   那段长注释 —— 那里是为「清空上一次的驳回理由」，是必须的；这里是顺带。
         */
        AuthorApply update = new AuthorApply();
        update.setStatus(target);
        update.setAuditUserId(operatorId);
        update.setAuditTime(now);
        // ★ status / auditUserId / auditTime 非 null，实体自己会写进 SET；
        //   updateTime 由 MybatisMetaObjectHandler 填充（实体非 null，updateFill 会执行）

        int rows = applyMapper.update(update, new LambdaUpdateWrapper<AuthorApply>()
                .eq(AuthorApply::getId, applyId)
                .eq(AuthorApply::getStatus, apply.getStatus())
                .set(AuthorApply::getAuditRemark, dto.getRemark()));

        if (rows == 0) {
            log.warn("作者申请审核：状态已被别人改过，本次放弃 | applyId={} 期望前置状态={} operatorId={}",
                    applyId, apply.getStatus(), operatorId);
            throw new BusinessException(ResultCode.AUTHOR_APPLY_STATUS_ILLEGAL);
        }

        /*
         * ★★ 通过 = 真的把 AUTHOR 角色给他。这一步才是这个功能的全部价值。
         *
         * 只改 author_apply.status 的话，申请人看到「已通过」，
         * 但进创作中心时会被 SecurityConfig 的 hasAnyRole("AUTHOR","ADMIN") 拦下 ——
         * 「申请通过了却还是进不去」，而且两个界面看起来都正常。
         */
        if (target == AuditStatus.APPROVED) {
            grantAuthorRole(apply.getUserId(), applyId);
        }

        /*
         * ★ 动作只可能是 APPROVE / REJECT。
         *   这里【没有】SUBMIT —— 提交申请不记流水：audit_log 记的是
         *   【状态流转】，而申请一出生就是「待审核」，没有「提交」这一次流转可记。
         *   和 {@code CommentServiceImpl#create} 是同一条规矩（那边的注释写得更长）。
         *
         * ★★ M3：这一行原来是 auditRecorder.record(...)，一次 Feign 写。
         *   现在是一条事务消息 —— 参数一个没变（applyId / action / remark），
         *   多出来的 operatorId 是本方法自己已经取好的那个（第 207 行），
         *   跟着载荷一起走是因为 MQ 的消费线程上没有 UserContext。
         *   完整的论证（以及「为什么这次可以把它放进载荷」）在 AuditRecordMessage 的类注释里。
         */
        auditProducer.publishAuthorApplyAudit(operatorId, applyId,
                target == AuditStatus.APPROVED ? AuditAction.APPROVE : AuditAction.REJECT,
                dto.getRemark());

        log.info("作者申请审核完成 | applyId={} userId={} {} → {} operatorId={}",
                applyId, apply.getUserId(), apply.getStatus(), target, operatorId);
    }

    /* ================================================================
     *  读者端
     * ================================================================ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submit(AuthorApplyCreateDTO dto) {
        Long userId = UserContext.requireUserId();

        /*
         * ① 已经是作者 → 拒绝。
         *
         * ★ 只查 AUTHOR，不查 ADMIN。管理员本来就能进创作中心
         *   （SecurityConfig 那条规则是 hasAnyRole("AUTHOR","ADMIN")），
         *   但他【不是作者】—— 用「你已经是作者了」把他拦下，是一句错话，
         *   他会去找「我什么时候成作者了」。
         *   管理员真想写书，让管理员在用户管理里给自己加一个 AUTHOR 角色即可。
         *
         * ★ 用 selectRoleCodesByUserId（不是 selectRoleIdsByUserId）：
         *   前者过滤了 r.deleted = 0 和 r.status = 1，也就是「真正生效的角色」。
         *   这里问的正是「他现在能不能进创作中心」，口径必须和鉴权一致。
         */
        if (userRoleMapper.selectRoleCodesByUserId(userId).contains(CommonConstant.ROLE_AUTHOR)) {
            throw new BusinessException(ResultCode.AUTHOR_APPLY_ALREADY_AUTHOR);
        }

        /*
         * ② 已经有一份在审 → 拒绝，不产生第二条。
         *
         * ★ 只拦【待审】的，不拦已驳回的：驳回之后重新提交是这个流程的正常
         *   下一步（用户改完笔名/说明再来一次），拦掉的话他就永远卡死了。
         *   已通过的那份更不拦 —— 走到这里说明他还没有 AUTHOR 角色，
         *   那多半是角色被管理员手工摘掉了，此时允许他重新申请是对的。
         */
        Long pending = applyMapper.selectCount(new LambdaQueryWrapper<AuthorApply>()
                .eq(AuthorApply::getUserId, userId)
                .eq(AuthorApply::getStatus, AuditStatus.PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException(ResultCode.AUTHOR_APPLY_PENDING);
        }

        AuthorApply apply = new AuthorApply();
        apply.setUserId(userId);
        /*
         * ★ trim 之后再存：笔名会显示在管理端列表和审核弹窗上，
         *   前后带空格的话排版会歪，而且「墨白」和「墨白 」在库里是两串不同的字。
         *   @NotBlank 保证 trim 之后不为空。
         */
        apply.setPenName(dto.getPenName().trim());
        /*
         * ★ 空字符串归一成 null。
         *   不归一的话「什么都没填」会存成一个空字符串，而全局配了
         *   default-property-inclusion: non_null —— 空字符串【不是】null，
         *   它会照样序列化出去，前端拿到的 intro 是 ""（假值判得出来，能工作），
         *   但库里从此有两种「没有创作说明」的表示（NULL 和 ''），
         *   统计「填了说明的比例」时要写成两条条件，而且总有人会漏掉一条。
         */
        apply.setIntro(StringUtils.hasText(dto.getIntro()) ? dto.getIntro().trim() : null);
        apply.setStatus(AuditStatus.PENDING);
        // ★ auditUserId / auditTime / auditRemark 留空 —— 还没人审过，它们就该是 null
        applyMapper.insert(apply);

        /*
         * ★★ 这里【不记】audit_log，理由和 CommentServiceImpl#create 完全一样：
         *   audit_log 记的是【状态流转】，而申请一出生就是「待审核」，
         *   没有「提交」这一次流转可记。对照作品：先建为草稿（0），
         *   再提交审核（0 → 1），那才是一次真实的流转，所以作品有 SUBMIT。
         *   ★ 提交时间已经在 create_time 里了，不会再丢信息。
         *   ★ 如果哪天有人给这里补一条 record(SUBMIT)，审核流水页上会出现
         *     两类「提交」，而它们的含义不同（作品是自己交的，申请也是自己交的
         *     —— 但作品的提交【会改状态】，申请的不会），统计口径就混了。
         */
        log.info("收到作者申请 | applyId={} userId={} penName={}",
                apply.getId(), userId, apply.getPenName());

        return apply.getId();
    }

    @Override
    public AuthorApplyVO mine() {
        Long userId = UserContext.requireUserId();

        /*
         * ★★ orderByDesc(id) + LIMIT 1，而不是 selectOne(只按 userId)。
         *
         *   同一个人可以有多份申请（被驳回后重新提交），而 MP 的 selectOne
         *   在查到多于一行时直接抛 TooManyResultsException → 500。
         *   这个 bug 只在「用户第二次提交之后」才出现 —— 也就是
         *   第一次走查的时候永远是好的。
         *   id 自增，倒序即「最近一份」。
         *
         * ★ .last("LIMIT 1") 里的 1 是编译期字面量，没有任何来自请求的字符，
         *   不是拼接注入。理由同 ContentUserLookup#userIdsByNickname 里的注释。
         */
        AuthorApply latest = applyMapper.selectOne(new LambdaQueryWrapper<AuthorApply>()
                .eq(AuthorApply::getUserId, userId)
                .orderByDesc(AuthorApply::getId)
                .last("LIMIT 1"));

        /* ★ 从来没申请过 → null。这不是错误，见 AuthorApplyService#mine */
        return latest == null ? null : AuthorApplyVO.forReader(latest);
    }

    /* ================================================================
     *  私有工具
     * ================================================================ */

    /**
     * 给申请人授予 AUTHOR 角色，并把他踢下线。
     *
     * <p><b>★★ 两件事缺一不可：</b>
     * <pre>
     *   insertUserRole  —— 不给角色，用户「申请通过了」却进不去创作中心
     *   removeAll       —— 不踢下线，改了角色对方界面上毫无变化
     * </pre>
     * 第二件特别容易漏：角色和权限是<b>登录时</b>从库里查出来写进 Redis 的
     * （见 {@code AuthServiceImpl}），{@code JwtAuthenticationFilter} 每请求
     * 只读 Redis 不查库。不踢的话，申请人重新打开创作中心仍然被拒 ——
     * 管理员看到「已通过」而用户说「还是进不去」，两边都没错。
     */
    private void grantAuthorRole(Long userId, Long applyId) {
        /*
         * ★ 查角色 id 而不是写死 1。种子里 AUTHOR 的 id 是不是 1 属于实现细节，
         *   写死的话换一套种子数据就会静默地给错角色（1 可能是 ADMIN）。
         */
        Long authorRoleId = roleMapper.selectRoleIdByCode(CommonConstant.ROLE_AUTHOR);
        if (authorRoleId == null) {
            /*
             * ★ 这是【数据坏了】，不是用户操作问题，所以 log.error。
             *   而且必须抛异常让事务回滚 —— 「通过了但没给角色」正是
             *   本模块最典型的半成品 bug，宁可让这次审核失败。
             *   （正常的种子数据里 AUTHOR 一定在；走到这里说明有人手工删过角色。）
             */
            log.error("系统里没有 AUTHOR 角色，作者申请无法通过 | applyId={} userId={}",
                    applyId, userId);
            throw new BusinessException(ResultCode.ROLE_NOT_FOUND,
                    "系统缺少 AUTHOR 角色，无法授予作者权限，请检查角色种子数据");
        }

        try {
            userRoleMapper.insertUserRole(userId, authorRoleId);
            log.info("作者申请通过：已授予 AUTHOR 角色 | applyId={} userId={} roleId={}",
                    applyId, userId, authorRoleId);
        } catch (DuplicateKeyException e) {
            /*
             * ★★ 撞 uk_user_role 唯一键，吞掉。
             *
             *   场景很真实：管理员手工给这个人加过 AUTHOR，后来又批了一份他早就
             *   提交的申请。用户的意图（成为作者）已经达成了，这里没有理由报 500。
             *   SysUserRoleMapper#insertUserRole 的注释里点名了「调用方要捕获它，
             *   见 AuthorApplyServiceImpl」—— 就是这一行。
             *
             * ★ 在事务里捕获唯一键冲突是安全的【因为用的是 MySQL】：
             *   InnoDB 不会因为一条 INSERT 撞唯一键而作废整个事务，
             *   后面的语句照常执行、照常提交。
             *   （PostgreSQL 会 —— 那边一旦报错，事务就必须整个回滚，
             *    同样的代码搬过去就是「审核通过了但状态没改」。）
             */
            log.info("作者申请通过：该用户已有 AUTHOR 角色，跳过授予 | applyId={} userId={}",
                    applyId, userId);
        }

        int kicked = tokenStore.removeAll(userId);
        log.info("作者申请通过：已清理申请人的登录态 | applyId={} userId={} 已清理={} 条",
                applyId, userId, kicked);
    }

    /**
     * 分页兜底。
     *
     * <p><b>★ {@code size} 的下界必须自己兜，不能只靠拦截器。</b>
     * {@code PaginationInnerInterceptor} 只管<b>上界</b>（{@code setMaxLimit(100)}），
     * 而 {@code size < 0} 在 MP 里是「<b>关闭分页</b>」的意思 ——
     * {@code ?size=-1} 会把整张表一次性查出来。
     * 本项目每个 Service 都各自兜了一次，这是约定。
     */
    private Page<AuthorApply> buildPage(long page, long size) {
        long safePage = page < 1 ? CommonConstant.DEFAULT_PAGE_CURRENT : page;
        long safeSize = size < 1 ? CommonConstant.DEFAULT_PAGE_SIZE : size;
        return new Page<>(safePage, safeSize);
    }
}
