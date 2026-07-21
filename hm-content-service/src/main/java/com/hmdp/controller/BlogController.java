package com.hmdp.controller;

import com.hmdp.config.OpenApiConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探店笔记控制器
 */
@RestController
@RequestMapping("/blog")
@Tag(name = "探店笔记接口", description = "发布探店笔记、点赞和笔记查询接口")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 发布探店笔记，用户身份以后端登录态为准。
     *
     * @param blog 探店笔记内容
     * @return 新增笔记id
     */
    @PostMapping
    @Operation(summary = "发布探店笔记", description = "当前登录用户发布探店笔记，后端自动绑定用户 id。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 当前用户点赞或取消点赞同一篇笔记。
     *
     * @param id 探店笔记id
     * @return 操作结果
     */
    @PutMapping("/like/{id}")
    @Operation(summary = "点赞或取消点赞笔记", description = "当前登录用户对指定笔记点赞；已点赞时再次调用会取消点赞。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result likeBlog(@Parameter(description = "笔记 id", example = "1") @PathVariable("id") Long id) {
        // 点赞状态需要同时维护数据库计数和 Redis 点赞集合
        return blogService.likeBlog(id);
    }

    /**
     * 根据id查询探店笔记详情。
     *
     * @param id 探店笔记id
     * @return 笔记详情，包含作者信息和当前用户点赞状态
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询探店笔记详情", description = "根据笔记 id 查询详情，并补充作者信息和点赞状态。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result queryBlogById(@Parameter(description = "笔记 id", example = "1") @PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 查询笔记前排点赞用户。
     *
     * @param id 探店笔记id
     * @return 最近点赞的用户列表
     */
    @GetMapping("/likes/{id}")
    @Operation(summary = "查询笔记点赞用户", description = "查询探店笔记的前排点赞用户，用于详情页展示。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result queryBlogLikes(@Parameter(description = "笔记 id", example = "1") @PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 分页查询当前登录用户发布的笔记。
     *
     * @param current 页码
     * @return 当前用户的笔记列表
     */
    @GetMapping("/of/me")
    @Operation(summary = "分页查询我的笔记", description = "查询当前登录用户发布的探店笔记列表。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result queryMyBlog(@Parameter(description = "页码，从 1 开始", example = "1") @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryMyBlog(current);
    }

    /**
     * 分页查询指定用户发布的笔记。
     *
     * @param userId 用户id
     * @param current 页码
     * @return 指定用户的笔记列表
     */
    @GetMapping("/of/user")
    @Operation(summary = "分页查询指定用户笔记", description = "查询指定用户发布的探店笔记列表。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result queryBlogByUserId(
            @Parameter(description = "用户 id", example = "1") @RequestParam("id") Long userId,
            @Parameter(description = "页码，从 1 开始", example = "1") @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        return blogService.queryBlogByUserId(userId, current);
    }

    /**
     * 分页查询热门探店笔记。
     *
     * @param current 页码
     * @return 热门笔记列表
     */
    @GetMapping("/hot")
    @Operation(summary = "分页查询热门笔记", description = "按点赞数倒序分页查询热门探店笔记。")
    public Result queryHotBlog(@Parameter(description = "页码，从 1 开始", example = "1") @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }
    /**
     * 滚动查询当前用户关注的人发布的探店笔记。
     *
     * @param max 上一次查询返回的最小时间戳，首次查询传当前时间戳即可
     * @param offset 相同时间戳的偏移量，用于滚动分页去重
     * @return 关注用户发布的笔记列表和下一页游标
     */
    @GetMapping("/of/follow")
    @Operation(summary = "查询关注用户笔记", description = "查询当前登录用户关注的人发布的探店笔记，用于个人主页关注 tab。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result queryBlogOfFollow(
            @Parameter(description = "滚动分页游标", example = "999999") @RequestParam("lastId") Long max,
            @Parameter(description = "偏移量", example = "0") @RequestParam(value = "offset", defaultValue = "0") Integer offset
    ) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
