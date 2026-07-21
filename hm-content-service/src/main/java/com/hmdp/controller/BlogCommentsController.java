package com.hmdp.controller;


import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
@Tag(name = "博客评论接口", description = "博客评论相关接口，当前 Controller 暂未提供具体接口方法")
public class BlogCommentsController {

}
