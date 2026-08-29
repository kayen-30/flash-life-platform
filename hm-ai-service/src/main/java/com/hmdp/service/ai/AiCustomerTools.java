package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import com.hmdp.api.ContentClient;
import com.hmdp.api.ShopClient;
import com.hmdp.api.TradeClient;
import com.hmdp.api.dto.BlogSummaryDTO;
import com.hmdp.api.dto.ShopSummaryDTO;
import com.hmdp.api.dto.VoucherSummaryDTO;
import jakarta.annotation.Resource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 工具通过各领域的内部 API 获取实时数据，不跨服务访问数据库。
 */
@Component
public class AiCustomerTools {

    private static final int BLOG_SUMMARY_LENGTH = 80;
    private static final int MAX_TOOL_RESULT_ITEMS = 5;
    // 与工具回合预算共用，保证外部工具文本不会绕过下一轮模型输入的成本预留。
    static final int MAX_TOOL_RESULT_CODE_POINTS = 1200;

    private final AiPrivacySanitizer privacySanitizer;

    @Resource
    private ShopClient shopClient;

    @Resource
    private TradeClient tradeClient;

    @Resource
    private ContentClient contentClient;

    public AiCustomerTools(AiPrivacySanitizer privacySanitizer) {
        this.privacySanitizer = privacySanitizer;
    }

    @Tool(name = "query_shop_by_name", value = "按店铺名称关键词查询店铺基础信息，适合回答店铺地址、人均、评分、营业时间等问题。")
    public String queryShopByName(@P("店铺名称关键词") String name) {
        if (StrUtil.isBlank(name)) {
            return "请提供店铺名称关键词。";
        }
        String keyword = privacySanitizer.limit(name.trim(), 80);
        List<ShopSummaryDTO> shops = shopClient.queryByName(keyword);
        if (shops == null || shops.isEmpty()) {
            return "没有查询到匹配的店铺。";
        }
        return sanitizeToolResult(shops.stream()
                .limit(MAX_TOOL_RESULT_ITEMS)
                .map(this::formatShop)
                .collect(Collectors.joining("\n")));
    }

    @Tool(name = "query_shop_vouchers", value = "根据店铺 id 查询店铺优惠券和秒杀券信息。")
    public String queryShopVouchers(@P("店铺 id") Long shopId) {
        if (shopId == null) {
            return "请提供店铺 id。";
        }
        List<VoucherSummaryDTO> vouchers = tradeClient.queryShopVouchers(shopId);
        if (vouchers == null || vouchers.isEmpty()) {
            return "该店铺暂未查询到优惠券。";
        }
        return sanitizeToolResult(vouchers.stream()
                .limit(MAX_TOOL_RESULT_ITEMS)
                .map(this::formatVoucher)
                .collect(Collectors.joining("\n")));
    }

    @Tool(name = "query_hot_blogs", value = "查询平台热门探店笔记，适合回答热门评价、种草内容和用户体验类问题。")
    public String queryHotBlogs(@P(value = "返回数量，默认 3，最大 5", required = false) Integer limit) {
        List<BlogSummaryDTO> blogs = contentClient.queryHotBlogs(blogLimit(limit));
        if (blogs == null || blogs.isEmpty()) {
            return "暂未查询到热门探店笔记。";
        }
        return sanitizeToolResult(blogs.stream()
                .limit(MAX_TOOL_RESULT_ITEMS)
                .map(this::formatBlog)
                .collect(Collectors.joining("\n")));
    }

    @Tool(name = "query_shop_blogs", value = "根据店铺 id 查询该店铺的热门探店笔记。")
    public String queryShopBlogs(@P("店铺 id") Long shopId,
                                 @P(value = "返回数量，默认 3，最大 5", required = false) Integer limit) {
        if (shopId == null) {
            return "请提供店铺 id。";
        }
        List<BlogSummaryDTO> blogs = contentClient.queryShopBlogs(shopId, blogLimit(limit));
        if (blogs == null || blogs.isEmpty()) {
            return "该店铺暂未查询到探店笔记。";
        }
        return sanitizeToolResult(blogs.stream()
                .limit(MAX_TOOL_RESULT_ITEMS)
                .map(this::formatBlog)
                .collect(Collectors.joining("\n")));
    }

    private String formatShop(ShopSummaryDTO shop) {
        return "店铺ID：" + shop.getId()
                + "，名称：" + valueOrUnknown(shop.getName())
                + "，商圈：" + valueOrUnknown(shop.getArea())
                + "，地址：" + valueOrUnknown(shop.getAddress())
                + "，人均：" + valueOrUnknown(shop.getAvgPrice())
                + "，评分：" + formatScore(shop.getScore())
                + "，营业时间：" + valueOrUnknown(shop.getOpenHours());
    }

    private String formatVoucher(VoucherSummaryDTO voucher) {
        return "优惠券ID：" + voucher.getId()
                + "，标题：" + valueOrUnknown(voucher.getTitle())
                + "，副标题：" + valueOrUnknown(voucher.getSubTitle())
                + "，支付金额：" + formatCent(voucher.getPayValue())
                + "，抵扣金额：" + formatCent(voucher.getActualValue())
                + "，库存：" + valueOrUnknown(voucher.getStock())
                + "，使用规则：" + valueOrUnknown(voucher.getRules());
    }

    private String formatBlog(BlogSummaryDTO blog) {
        return "笔记ID：" + blog.getId()
                + "，店铺ID：" + valueOrUnknown(blog.getShopId())
                + "，标题：" + valueOrUnknown(blog.getTitle())
                + "，点赞：" + valueOrUnknown(blog.getLiked())
                + "，内容摘要：" + abbreviate(blog.getContent(), BLOG_SUMMARY_LENGTH);
    }

    private int blogLimit(Integer limit) {
        return limit == null ? 3 : Math.min(Math.max(limit, 1), 5);
    }

    private String formatScore(Integer score) {
        return score == null ? "未知" : String.format("%.1f", score / 10.0);
    }

    private String formatCent(Long value) {
        return value == null ? "未知" : String.format("%.2f元", value / 100.0);
    }

    private String abbreviate(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return "无";
        }
        return value.codePointCount(0, value.length()) <= maxLength
                ? value : privacySanitizer.limit(value, maxLength) + "...";
    }

    private String valueOrUnknown(Object value) {
        return value == null ? "未知" : value.toString();
    }

    /**
     * 工具结果会被模型再次读取，因此统一脱敏并限制上下文大小，防止 UGC 扩大隐私和成本边界。
     */
    private String sanitizeToolResult(String value) {
        return privacySanitizer.limit(privacySanitizer.sanitizeForModel(value), MAX_TOOL_RESULT_CODE_POINTS);
    }
}
