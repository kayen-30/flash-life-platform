package com.hmdp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.api.dto.BlogSummaryDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 服务通过摘要接口读取内容，不允许直接连接内容数据库。
 */
@RestController
@RequestMapping("/internal/blogs")
public class InternalBlogController {

    @Resource
    private IBlogService blogService;

    @GetMapping("/hot")
    public List<BlogSummaryDTO> queryHotBlogs(@RequestParam("limit") Integer limit) {
        return blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(1, normalizeLimit(limit)))
                .getRecords()
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @GetMapping("/by-shop")
    public List<BlogSummaryDTO> queryShopBlogs(@RequestParam("shopId") Long shopId,
                                                @RequestParam("limit") Integer limit) {
        return blogService.query()
                .eq("shop_id", shopId)
                .orderByDesc("liked")
                .page(new Page<>(1, normalizeLimit(limit)))
                .getRecords()
                .stream()
                .map(this::toSummary)
                .toList();
    }

    private int normalizeLimit(Integer limit) {
        return limit == null ? 3 : Math.min(Math.max(limit, 1), 5);
    }

    private BlogSummaryDTO toSummary(Blog blog) {
        BlogSummaryDTO dto = new BlogSummaryDTO();
        dto.setId(blog.getId());
        dto.setShopId(blog.getShopId());
        dto.setTitle(blog.getTitle());
        dto.setContent(blog.getContent());
        dto.setLiked(blog.getLiked());
        return dto;
    }
}
