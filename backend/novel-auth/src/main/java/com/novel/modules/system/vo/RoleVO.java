package com.novel.modules.system.vo;

import com.novel.common.constant.CommonConstant;
import com.novel.modules.system.entity.SysRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色出参。
 *
 * <p><b>★ 两个用途，同一个 VO：</b>
 * <ol>
 *   <li>用户管理页的「编辑角色」弹窗要一份角色清单渲染多选框（S6 就有）；</li>
 *   <li>角色管理页的列表（S13 加的）。</li>
 * </ol>
 * 合成一个接口一个 VO，而不是「给弹窗一个精简版、给列表一个详细版」：
 * 同一份数据在两个页面上不一致（比如列表算出了用户数、弹窗没有）
 * 是那种「看起来只是少个字段」的偏差，但会让两个页面的行为分叉。
 *
 * <p><b>★★ {@code status} 也要返回，不能只给启用的角色：</b>
 * 一个用户可能还挂着一个<b>已停用</b>的角色（停用不影响已有授权关系），
 * 编辑弹窗如果只列出启用的角色，那个已停用的角色就会显示成「未勾选」——
 * 管理员一保存，这个关联就被悄悄删掉了，而他什么都没做。
 *
 * <p><b>★★ {@code builtin} 和 {@code userCount} 是 S13 加的，
 * 存在的意义是「让前端能把按钮变灰，而不是让后端拿错误码去挡」。</b>
 * 两者都是<b>预判</b>：前端的删除按钮靠它们决定可不可点，
 * 而真正的拦截仍然在后端（{@code ROLE_BUILTIN} / {@code ROLE_IN_USE}）——
 * 前端那层只是「少一次注定失败的点击」，<b>不是安全措施</b>。
 * 这和作者申请页「非待审行禁用按钮」是同一条思路：
 * 与其让管理员点了再看到一句红字，不如一开始就告诉他这条路不通。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "角色信息")
public class RoleVO {

    @Schema(description = "角色 ID", example = "2")
    private Long id;

    @Schema(description = "角色标识", example = "AUTHOR")
    private String roleCode;

    @Schema(description = "角色名", example = "作者")
    private String roleName;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "状态：1 启用，0 停用", example = "1")
    private Integer status;

    @Schema(description = "是否内置角色。内置角色（ADMIN / AUTHOR / READER）不可删除、停用、改标识",
            example = "true")
    private Boolean builtin;

    /**
     * 这个角色下挂了多少个用户。
     *
     * <p>★ 它是「删除按钮能不能点」的依据（{@code 0} 才能点）。
     * 为 0 而不是 null —— 由 Service 侧补 0，
     * 见 {@code SysUserRoleMapper#countGroupByRoleId}。
     */
    @Schema(description = "该角色下的用户数。删除角色的前置条件就是它为 0", example = "1")
    private Long userCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * @param role      {@code sys_role} 的一行
     * @param userCount 这个角色下的用户数。★ 传 {@code null} 会被当成 0 ——
     *                  宁可显示「0 个用户」也不显示 null：
     *                  界面上 null 会被渲染成空白，而空白在「能不能删」
     *                  这个问题上和 0 的意思完全相反
     */
    public static RoleVO of(SysRole role, Long userCount) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setRemark(role.getRemark());
        vo.setStatus(role.getStatus());
        /*
         * ★ builtin 由 roleCode 算出来，不从库里读。
         *   「哪几个是内置角色」是代码里的事实（CommonConstant），
         *   库里没有这一列，也不该有 —— 加一列的话它就能被 UPDATE 改掉，
         *   而它的意义正是「不许被改」。
         */
        vo.setBuiltin(CommonConstant.BUILTIN_ROLE_CODES.contains(role.getRoleCode()));
        vo.setUserCount(userCount == null ? 0L : userCount);
        vo.setCreateTime(role.getCreateTime());
        return vo;
    }
}
