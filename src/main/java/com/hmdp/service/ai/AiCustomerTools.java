package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AiCustomerTools {

    private static final int BLOG_SUMMARY_LENGTH = 80;

    @Resource
    private IShopService shopService;

    @Resource
    private VoucherMapper voucherMapper;

    @Resource
    private IBlogService blogService;

    /**
     * 让 Agent 能按店铺名回查业务库，避免只靠模型猜测店铺信息。
     */
    @Tool(name = "query_shop_by_name", description = "按店铺名称关键词查询店铺基础信息，适合回答店铺地址、人均、评分、营业时间等问题。")
    public String queryShopByName(@ToolParam(description = "店铺名称关键词") String name) {
        if (StrUtil.isBlank(name)) {
            return "请提供店铺名称关键词。";
        }
        List<Shop> shops = shopService.query()
                .like("name", name)
                .last("limit 3")
                .list();
        if (shops.isEmpty()) {
            return "没有查询到匹配的店铺。";
        }
        return shops.stream()
                .map(this::formatShop)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 让 Agent 能读取当前店铺可用优惠券，回答优惠和秒杀相关追问。
     */
    @Tool(name = "query_shop_vouchers", description = "根据店铺 id 查询店铺优惠券和秒杀券信息。")
    public String queryShopVouchers(@ToolParam(description = "店铺 id") Long shopId) {
        if (shopId == null) {
            return "请提供店铺 id。";
        }
        List<Voucher> vouchers = voucherMapper.queryVoucherOfShop(shopId);
        if (vouchers.isEmpty()) {
            return "该店铺暂未查询到优惠券。";
        }
        return vouchers.stream()
                .limit(5)
                .map(this::formatVoucher)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 让 Agent 能结合平台热门内容回答“大家怎么评价”一类问题。
     */
    @Tool(name = "query_hot_blogs", description = "查询平台热门探店笔记，适合回答热门评价、种草内容和用户体验类问题。")
    public String queryHotBlogs(@ToolParam(required = false, description = "返回数量，默认 3，最多 5") Integer limit) {
        int size = limit == null ? 3 : Math.min(Math.max(limit, 1), 5);
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(1, size));
        List<Blog> blogs = page.getRecords();
        if (blogs.isEmpty()) {
            return "暂未查询到热门探店笔记。";
        }
        return blogs.stream()
                .map(this::formatBlog)
                .collect(Collectors.joining("\n"));
    }

    private String formatShop(Shop shop) {
        return "店铺ID：" + shop.getId()
                + "，名称：" + valueOrEmpty(shop.getName())
                + "，商圈：" + valueOrEmpty(shop.getArea())
                + "，地址：" + valueOrEmpty(shop.getAddress())
                + "，人均：" + valueOrEmpty(shop.getAvgPrice())
                + "，评分：" + formatScore(shop.getScore())
                + "，营业时间：" + valueOrEmpty(shop.getOpenHours());
    }

    private String formatVoucher(Voucher voucher) {
        return "优惠券ID：" + voucher.getId()
                + "，标题：" + valueOrEmpty(voucher.getTitle())
                + "，副标题：" + valueOrEmpty(voucher.getSubTitle())
                + "，支付金额：" + formatCent(voucher.getPayValue())
                + "，抵扣金额：" + formatCent(voucher.getActualValue())
                + "，库存：" + valueOrEmpty(voucher.getStock())
                + "，使用规则：" + valueOrEmpty(voucher.getRules());
    }

    private String formatBlog(Blog blog) {
        return "笔记ID：" + blog.getId()
                + "，标题：" + valueOrEmpty(blog.getTitle())
                + "，点赞：" + valueOrEmpty(blog.getLiked())
                + "，内容摘要：" + abbreviate(blog.getContent(), BLOG_SUMMARY_LENGTH);
    }

    private String formatScore(Integer score) {
        if (score == null) {
            return "未知";
        }
        return String.format("%.1f", score / 10.0);
    }

    private String formatCent(Long value) {
        if (value == null) {
            return "未知";
        }
        return String.format("%.2f元", value / 100.0);
    }

    private String abbreviate(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return "无";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String valueOrEmpty(Object value) {
        return value == null ? "未知" : value.toString();
    }
}
