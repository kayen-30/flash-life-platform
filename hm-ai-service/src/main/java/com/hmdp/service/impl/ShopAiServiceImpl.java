package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.api.ShopClient;
import com.hmdp.api.dto.ShopSummaryDTO;
import com.hmdp.service.IShopAiService;
import com.hmdp.service.AiRequestGuard;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class ShopAiServiceImpl implements IShopAiService {

    private final ChatClient chatClient;
    private final AiRequestGuard requestGuard;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${hmdp.ai.rate-limit.recommend-per-minute:10}")
    private int recommendRateLimit;

    @Value("${hmdp.ai.recommend-cache-ttl:6h}")
    private Duration recommendCacheTtl;

    @Resource
    private ShopClient shopClient;

    /**
     * 构建 Spring AI 客户端；未配置模型时保留空值，便于项目无密钥也能正常启动。
     */
    public ShopAiServiceImpl(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                             AiRequestGuard requestGuard,
                             StringRedisTemplate stringRedisTemplate) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        this.chatClient = builder == null ? null : builder
                .defaultSystem("你是大众点评平台的本地生活推荐助手，回答要真实、克制、适合展示给用户。")
                .build();
        this.requestGuard = requestGuard;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 根据店铺资料生成 AI 推荐语，用于店铺详情页的智能亮点展示。
     */
    @Override
    public Result generateRecommend(Long shopId) {
        Long userId = requestGuard.currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        String cacheKey = "cache:ai:shop-recommend:" + shopId;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return Result.ok(cached);
        }
        if (chatClient == null) {
            // 默认关闭模型调用，避免没有 API Key 时影响本地学习环境启动。
            return Result.fail("AI 服务未启用，请在后端启动配置中设置 DEEPSEEK_API_KEY，并开启 Spring AI Chat。");
        }
        if (!requestGuard.tryAcquire("recommend", userId, recommendRateLimit)) {
            return Result.fail("AI 推荐请求过于频繁，请稍后再试");
        }

        // AI 服务只通过店铺公开契约读取数据，不直接依赖店铺库或实体。
        ShopSummaryDTO shop = shopClient.queryById(shopId);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        try {
            String content = chatClient.prompt()
                    .user(buildRecommendPrompt(shop))
                    .call()
                    .content();
            // 推荐语按店铺复用，避免相同资料重复消耗模型额度。
            stringRedisTemplate.opsForValue().set(cacheKey, content, recommendCacheTtl);
            return Result.ok(content);
        } catch (RuntimeException e) {
            // 模型服务属于外部依赖，失败时给前端稳定响应，详细原因留在日志中排查。
            log.warn("生成店铺 AI 推荐失败，shopId={}", shopId, e);
            return Result.fail("AI 推荐生成失败，请稍后再试");
        }
    }

    /**
     * 组装店铺上下文，约束模型只基于已有业务数据生成推荐语。
     */
    private String buildRecommendPrompt(ShopSummaryDTO shop) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于以下店铺信息，生成一段 80 字以内的中文推荐语。")
                .append("不要编造不存在的信息，不要输出 Markdown。");
        appendField(prompt, "店名", shop.getName());
        appendField(prompt, "商圈", shop.getArea());
        appendField(prompt, "地址", shop.getAddress());
        appendField(prompt, "人均", formatAvgPrice(shop.getAvgPrice()));
        appendField(prompt, "评分", formatScore(shop.getScore()));
        appendField(prompt, "销量", shop.getSold());
        appendField(prompt, "评论数", shop.getComments());
        appendField(prompt, "营业时间", shop.getOpenHours());
        return prompt.toString();
    }

    private void appendField(StringBuilder prompt, String name, Object value) {
        if (value != null && !"".equals(value.toString().trim())) {
            prompt.append('\n').append(name).append("：").append(value);
        }
    }

    private String formatAvgPrice(Long avgPrice) {
        return avgPrice == null ? null : "约 " + avgPrice + " 元/人";
    }

    private String formatScore(Integer score) {
        if (score == null) {
            return null;
        }
        return String.format("%.1f 分", score / 10.0);
    }
}
