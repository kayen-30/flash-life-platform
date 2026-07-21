package com.hmdp.controller;

import com.hmdp.config.OpenApiConfig;
import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户关注控制器
 */
@RestController
@RequestMapping("/follow")
@Tag(name = "关注接口", description = "用户关注、取关、关注状态和共同关注接口")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注或取消关注用户。
     *
     * @param followUserId 被关注用户id
     * @param isFollow 是否关注
     * @return 操作结果
     */
    @PutMapping("/{id}/{isFollow}")
    @Operation(summary = "关注或取消关注用户", description = "当前登录用户关注或取消关注指定用户。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result follow(
            @Parameter(description = "被关注用户 id", example = "2") @PathVariable("id") Long followUserId,
            @Parameter(description = "true 表示关注，false 表示取关", example = "true") @PathVariable("isFollow") Boolean isFollow
    ) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 查询当前用户是否关注目标用户。
     *
     * @param followUserId 被关注用户id
     * @return 是否已关注
     */
    @GetMapping("/or/not/{id}")
    @Operation(summary = "查询是否已关注", description = "判断当前登录用户是否关注指定用户。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result isFollow(@Parameter(description = "被关注用户 id", example = "2") @PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 查询当前用户与目标用户的共同关注。
     *
     * @param followUserId 目标用户id
     * @return 共同关注用户列表
     */
    @GetMapping("/common/{id}")
    @Operation(summary = "查询共同关注", description = "查询当前登录用户与指定用户都关注的用户列表。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result followCommons(@Parameter(description = "目标用户 id", example = "2") @PathVariable("id") Long followUserId) {
        return followService.followCommons(followUserId);
    }
    /**
     * 查询当前登录用户关注数量，个人主页关注 tab 使用。
     *
     * @return 当前用户关注的人数
     */
    @GetMapping("/count")
    @Operation(summary = "查询我的关注数量", description = "查询当前登录用户已经关注的人数。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result countMyFollows() {
        return followService.countMyFollows();
    }
}
