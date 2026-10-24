package com.hmdp.ai.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.security.AiPrivacySanitizer;
import com.hmdp.ai.customer.AiBusinessFacts;
import com.hmdp.ai.agent.log.AgentExecutionLogService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import cn.hutool.json.JSONUtil;
import java.math.BigDecimal;
import java.util.Map;
import com.hmdp.ai.memory.AiConversationService;
import com.hmdp.ai.execution.AiExecutionBudget;
import java.time.Duration;
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
    private final AiConversationService conversationService;

    @Resource
    private ShopClient shopClient;

    @Resource
    private TradeClient tradeClient;

    @Resource
    private ContentClient contentClient;

    @Resource
    private AiToolCallBudget toolCallBudget;

    @Resource
    private AgentExecutionLogService executionLog;

    public AiCustomerTools(AiPrivacySanitizer privacySanitizer, AiConversationService conversationService) {
        this.privacySanitizer = privacySanitizer;
        this.conversationService = conversationService;
    }

    @Tool(name = "query_shop_by_name", value = "按店铺名称关键词查询地址、人均、评分、营业时间。参数只填店名，查询、比较、帮我看等请求动词不是店名的一部分；真实店名本身包含这些字时保留。")
    public String queryShopByName(@P("店铺名称关键词") String name) {
        if (AiBusinessFacts.selection() != null) AiBusinessFacts.selection().clear();
        if (StrUtil.isBlank(name)) {
            return outcome("query_shop_by_name", "SHOP_REQUIRED", "请提供店铺名称关键词。");
        }
        String keyword = privacySanitizer.limit(name.trim(), 80);
        requireToolTime();
        // 明确查新目标时先清除旧实体；远程失败也不能让模型回退到旧店。
        conversationService.clearShop(false);
        List<ShopSummaryDTO> shops = shopClient.queryByName(keyword);
        if (shops == null || shops.isEmpty()) {
            conversationService.clearShop(false);
            return outcome("query_shop_by_name", "SHOP_NOT_FOUND", "没有查询到匹配的店铺。");
        }
        if (shops.size() == 1) {
            conversationService.confirmShop(shops.get(0).getId(), shops.get(0).getName(), "name_lookup");
            AiBusinessFacts.toolStatus("query_shop_by_name", com.hmdp.ai.customer.CustomerTask.Status.COMPLETED, "SHOP_RESOLVED");
        } else {
            conversationService.clearShop(true);
            AiBusinessFacts.toolStatus("query_shop_by_name", com.hmdp.ai.customer.CustomerTask.Status.NEEDS_CLARIFICATION, "SHOP_AMBIGUOUS");
        }
        return (shops.size() > 1 ? "存在多个同名候选，必须先让用户确认，不能自行选择。\n" : "")
                + sanitizeToolResult(shops.stream()
                .limit(MAX_TOOL_RESULT_ITEMS)
                .map(this::formatShop)
                .collect(Collectors.joining("\n")));
    }

    /** 已确认ID可直接核对本轮基础信息，不依赖模型再次从历史中提取店名。 */
    @Tool(name = "query_shop_by_id", value = "用服务端已确认的店铺ID查询本轮地址、营业时间、人均或评分，不使用历史答复代替查询。")
    public String queryShopById(@P("服务端实体或真实工具结果中的店铺ID，不能从名称数字猜测") Long shopId) {
        AiConversationService.ShopState state = conversationService.currentShop();
        if (state != null && (state.ambiguous() || "unresolved".equals(state.resolvedFrom()))) {
            return outcome("query_shop_by_id", "SHOP_UNRESOLVED", "未确定唯一店铺，请先确认具体店铺。");
        }
        Long id = shopId != null ? shopId : state == null ? null : state.shopId();
        if (id == null || id <= 0) return outcome("query_shop_by_id", "SHOP_REQUIRED", "请提供具体店铺名称。");
        if (state == null || !id.equals(state.shopId())) {
            if (AiBusinessFacts.selection() != null) AiBusinessFacts.selection().clear();
        }
        requireToolTime();
        ShopSummaryDTO shop = shopClient.queryById(id);
        if (shop == null) { conversationService.clearShop(false); return outcome("query_shop_by_id", "SHOP_NOT_FOUND", "没有查询到匹配的店铺。"); }
        conversationService.confirmShop(shop.getId(), shop.getName(), "id_lookup");
        AiBusinessFacts.toolStatus("query_shop_by_id", com.hmdp.ai.customer.CustomerTask.Status.COMPLETED, "SHOP_RESOLVED");
        return describeVerifiedShop(shop);
    }

    /** 名称解析与查券在一次工具调用中完成，内部查询仍单独扣预算、记日志。 */
    @Tool(name = "query_shop_vouchers", value = "查询店铺优惠券。给出店名时直接传shopName，工具会找店并查券，无需先调用query_shop_by_name；同名候选会要求澄清。")
    public String queryShopVouchers(
            @P(value = "服务端实体区域或查店结果中的店铺id，禁止从店名数字猜ID；有新店名时省略", required = false) Long shopId,
            @P(value = "只填用户指明的真实店名，去掉比较、查询等请求动词；优先于旧shopId；代词不要填此字段", required = false) String shopName) {
        // 远程刷新失败也不能保留刷新前的候选和获取时间。
        if (AiBusinessFacts.selection() != null) AiBusinessFacts.selection().clear();
        if (StrUtil.isNotBlank(shopName)) {
            String name = privacySanitizer.limit(shopName.trim(), 80);
            ToolExecutionRequest lookup = ToolExecutionRequest.builder().name("query_shop_by_name")
                    .arguments(JSONUtil.toJsonStr(Map.of("name", name))).build();
            String found = executionLog.executeToolCall(lookup.name(), lookup.arguments(),
                    () -> toolCallBudget.executeTool(lookup, null, (request, memoryId) -> queryShopByName(name)));
            AiConversationService.ShopState state = conversationService.currentShop();
            // 新目标不唯一或不存在时已经清理旧实体，绝不使用调用参数里的旧ID兜底。
            if (state == null || state.shopId() == null || state.ambiguous()) return outcome("query_shop_vouchers", "SHOP_UNRESOLVED", found);
            shopId = state.shopId();
        }
        shopId = resolveShopId(shopId);
        if (shopId == null) return outcome("query_shop_vouchers", "SHOP_REQUIRED", "未确定唯一店铺，请先询问用户要查看哪一家，不能查询平台热门作为替代。");
        requireToolTime();
        Integer useWeekday = AiBusinessFacts.selection() == null ? null : AiBusinessFacts.selection().useWeekday();
        List<VoucherSummaryDTO> vouchers = useWeekday == null ? tradeClient.queryShopVouchers(shopId)
                : tradeClient.queryShopVouchers(shopId, useWeekday);
        if (vouchers == null) throw new AiBusinessFacts.EvidenceUnavailableException("voucher_snapshot_missing");
        if (AiBusinessFacts.selection() != null) AiBusinessFacts.selection().capture(vouchers, privacySanitizer);
        AiBusinessFacts.toolStatus("query_shop_vouchers", com.hmdp.ai.customer.CustomerTask.Status.COMPLETED, "VOUCHERS_QUERIED");
        AiConversationService.ShopState state = conversationService.currentShop();
        String shop = "店铺：" + (state == null ? shopId : field(state.shopName(), 40)) + "（ID：" + shopId + "）\n";
        if (vouchers.isEmpty()) return shop + "该店铺暂未查询到上架优惠券。";
        // 字段各自限长，避免截断整段文本时把库存未知或不可购的状态裁掉。
        return shop + vouchers.stream().limit(MAX_TOOL_RESULT_ITEMS).map(this::formatVoucher)
                .collect(Collectors.joining("\n"))
                + (vouchers.size() > MAX_TOOL_RESULT_ITEMS ? "\n仅展示前5张券。" : "");
    }

    /** 已有页面预取调用沿用原Java入口；启用Skill时仍由外层禁止预取。 */
    public String queryShopVouchers(Long shopId) {
        return queryShopVouchers(shopId, null);
    }

    /** 仅读取本轮同店快照；不额外远程查询，也不能读取其他用户或旧轮次的券。 */
    @Tool(name = "inspect_voucher_conditions", value = "批量读取本轮query_shop_vouchers已展示券的完整规则，检查预算、比较和使用条件时使用；最多5个ID，金额单位为分。")
    public String inspectVoucherConditions(@P("本轮已展示的优惠券ID列表，不是店铺ID") List<Long> voucherIds) {
        if (AiBusinessFacts.selection() == null) throw new AiBusinessFacts.EvidenceUnavailableException();
        return AiBusinessFacts.selection().inspect(voucherIds);
    }

    @Tool(name = "query_hot_blogs", value = "仅查询全平台热门探店笔记。用户问某家店、它、刚才那家的笔记时禁止用本工具替代 query_shop_blogs。")
    public String queryHotBlogs(@P(value = "返回数量，默认 3，最大 5", required = false) Integer limit) {
        requireToolTime();
        List<BlogSummaryDTO> blogs = contentClient.queryHotBlogs(blogLimit(limit));
        AiBusinessFacts.toolStatus("query_hot_blogs", com.hmdp.ai.customer.CustomerTask.Status.COMPLETED, "BLOGS_QUERIED");
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
        shopId = resolveShopId(shopId);
        if (shopId == null) return outcome("query_shop_blogs", "SHOP_REQUIRED", "未确定唯一店铺，请先询问用户要查看哪一家，不能查询平台热门作为替代。");
        requireToolTime();
        List<BlogSummaryDTO> blogs = contentClient.queryShopBlogs(shopId, blogLimit(limit));
        AiBusinessFacts.toolStatus("query_shop_blogs", com.hmdp.ai.customer.CustomerTask.Status.COMPLETED, "BLOGS_QUERIED");
        if (blogs == null || blogs.isEmpty()) {
            return "该店铺暂未查询到探店笔记。";
        }
        return sanitizeToolResult(blogs.stream()
                .limit(MAX_TOOL_RESULT_ITEMS)
                .map(this::formatBlog)
                .collect(Collectors.joining("\n")));
    }

    /** 页面/模型给出的新ID也要查存在性，不能把未知ID持久化为会话事实。 */
    private Long resolveShopId(Long requested) {
        AiConversationService.ShopState state = conversationService.currentShop();
        // 新目标查无结果/有歧义时，旧历史中的ID不能重新激活；必须先确认新的目标。
        if (state != null && (state.ambiguous() || "unresolved".equals(state.resolvedFrom()))) return null;
        Long id = requested != null ? requested : state == null ? null : state.shopId();
        if (id == null || id <= 0) return null;
        if (state != null && id.equals(state.shopId())) return id;
        requireToolTime();
        // 组合工具的额外远程查店也占一次预算，不能把网络调用藏在单个工具名下。
        AiExecutionBudget budget = AiExecutionBudget.current();
        if (budget != null && !budget.tryConsumeToolCall()) throw new AiToolCallBudgetExceededException();
        ShopSummaryDTO shop = shopClient.queryById(id);
        if (shop == null) { conversationService.clearShop(false); return null; }
        conversationService.confirmShop(shop.getId(), shop.getName(), "id_lookup");
        return shop.getId();
    }

    private void requireToolTime() {
        AiExecutionBudget budget = AiExecutionBudget.current();
        if (budget != null) budget.requireTime(Duration.ofSeconds(4));
    }

    private String outcome(String tool, String reason, String message) {
        AiBusinessFacts.toolStatus(tool, com.hmdp.ai.customer.CustomerTask.Status.NEEDS_CLARIFICATION, reason);
        return message;
    }

    /** 已核实DTO的统一脱敏展示；普通方法不会注册成模型工具。 */
    public String describeVerifiedShop(ShopSummaryDTO shop) {
        return sanitizeToolResult(formatShop(shop));
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

    /** 金额与可用性由服务端直接展示，UGC名称和规则只作为有标签的原始资料。 */
    private String formatVoucher(VoucherSummaryDTO voucher) {
        String availability = switch (voucher.getAvailability() == null ? "UNKNOWN" : voucher.getAvailability()) {
            case "OFF_SHELF" -> "已下架，不能购买";
            case "EXPIRED" -> "活动已结束，不能购买";
            case "NOT_STARTED" -> "活动尚未开始，暂不能购买";
            case "SOLD_OUT" -> "已售罄，不能购买";
            case "IN_STOCK_AT_QUERY" -> voucher.getStock() != null && voucher.getStock() > 0
                    ? "查询时有库存，最终以提交订单校验为准" : "库存信息不一致，无法确认是否可购买";
            default -> "信息不完整，无法确认是否可购买";
        };
        return "优惠券ID：" + voucher.getId() + "，状态：" + availability
                + "，支付金额：" + formatCent(voucher.getPayValue())
                + "，抵扣金额：" + formatCent(voucher.getActualValue())
                + "，库存：" + valueOrUnknown(voucher.getStock())
                + "，活动开始：" + valueOrUnknown(voucher.getBeginTime())
                + "，活动结束：" + valueOrUnknown(voucher.getEndTime())
                + "，商家标题：『" + field(voucher.getTitle(), 40) + "』"
                + "，商家规则摘要：『" + field(voucher.getRules(), 80) + "』（检查适用条件须读取完整规则）";
    }

    private String field(String value, int maxLength) {
        return privacySanitizer.limit(privacySanitizer.sanitizeForModel(valueOrUnknown(value))
                .replace('\n', ' ').replace('\r', ' '), maxLength);
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
        return value == null ? "未知" : BigDecimal.valueOf(value, 2).toPlainString() + "元";
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
