package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 给用户赋角色的入参（<b>覆盖式</b>，不是增量）。
 *
 * <p><b>★ 覆盖式而不是「加一个 / 删一个」：</b>
 * 界面上的交互是「一组多选框 + 保存」，管理员看到的就是最终结果。
 * 做成增量的话，取消勾选要发一个「删除」请求，而管理员看不到
 * 「哪些被删了」—— 保存成功之后也没法确认结果对不对。
 * 覆盖式的语义是「界面上勾成什么样，库里就是什么样」，
 * 这在两个请求之间是原子的（同一个事务里先全删再全插）。
 *
 * <p><b>★★ 不允许传空列表，这是刻意的：</b>
 * 一个<b>什么角色都没有</b>的用户能正常登录，但 {@code roles} 是空数组，
 * 前端拿不到角色，表现是「登录成功但什么页面都进不去」——
 * 见 {@code AuthServiceImpl} 注册方法上那段注释。
 * 而且它和「封禁」在效果上难以区分，管理员根本分不清是自己删错了还是系统坏了。
 *
 * <p>要让一个人不能用，正确做法是<b>封禁</b>（{@code PUT /{id}/status}），
 * 那是可逆的、界面上看得见的。所以这里用 {@code @NotEmpty} 直接把空列表挡在 400。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "用户赋角色入参（覆盖式）")
public class UserRolesDTO {

    @Schema(description = "该用户最终应有的全部角色 id", example = "[1, 2]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请提供角色列表")
    @NotEmpty(message = "请至少给这个用户保留一个角色；要让某人无法使用，请改用「封禁」")
    private List<Long> roleIds;
}
