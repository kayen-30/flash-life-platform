package com.hmdp.controller;

import com.hmdp.config.OpenApiConfig;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/user")
@Tag(name = "用户接口", description = "验证码、登录、当前用户和用户详情接口")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @PostMapping("code")
    @Operation(summary = "发送手机验证码", description = "根据手机号生成验证码并写入 Redis，登录接口会使用该验证码。")
    public Result sendCode(@Parameter(description = "手机号", example = "13800138000") @RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    @PostMapping("/login")
    @Operation(summary = "手机号验证码登录", description = "使用手机号和验证码登录，成功后返回 token。")
    public Result login(@RequestBody LoginFormDTO loginForm) {
        return userService.login(loginForm);
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "根据请求头中的 authorization token 清理登录状态。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result logout(HttpServletRequest request) {
        return userService.logout(request.getHeader("authorization"));
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前登录用户", description = "从登录拦截器保存的 ThreadLocal 中读取当前用户信息。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result me() {
        return Result.ok(UserHolder.getUser());
    }

    /**
     * 当前用户签到，签到状态按月写入 Redis bitmap。
     * @return 无
     */
    @PostMapping("/sign")
    @Operation(summary = "用户签到", description = "使用 Redis bitmap 记录当前用户今天的签到状态。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result sign() {
        return userService.sign();
    }

    /**
     * 查询当前用户本月连续签到天数。
     * @return 连续签到天数
     */
    @GetMapping("/sign/count")
    @Operation(summary = "查询连续签到天数", description = "基于 Redis bitmap 统计当前用户本月截至今天的连续签到天数。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result signCount() {
        return userService.signCount();
    }

    /**
     * 根据id查询用户基础信息，供他人主页展示。
     * @param userId 用户id
     * @return 用户昵称和头像
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询用户基础信息", description = "根据用户 id 查询昵称和头像，用于用户主页展示。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result queryUserById(@Parameter(description = "用户 id", example = "1") @PathVariable("id") Long userId) {
        // 主页只需要公开展示字段，避免把手机号、密码等实体字段返回给前端
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());
        return Result.ok(userDTO);
    }

    @GetMapping("/info/{id}")
    @Operation(summary = "查询用户详情", description = "根据用户 id 查询用户资料，隐藏创建和更新时间。")
    public Result info(@Parameter(description = "用户 id", example = "1") @PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }
}
