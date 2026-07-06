package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopAiService;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ShopAiServiceImpl implements IShopAiService {

    private final ChatClient chatClient;

    @Resource
    private IShopService shopService;

    /**
     * 构建 Spring AI 客户端；未配置模型时保留空值，便于项目无密钥也能正常启动。
     */
    public ShopAiServiceImpl(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        this.chatClient = builder == null ? null : builder
                .defaultSystem("你是大众点评平台的本地生活推荐助手，回答要真实、克制、适合展示给用户。")
                .build();
    }

    /**
     * 根据店铺资料生成 AI 推荐语，用于店铺详情页的智能亮点展示。
     */
    @Override
    public Result generateRecommend(Long shopId) {
        if (chatClient == null) {
            // 默认关闭模型调用，避免没有 API Key 时影响本地学习环境启动。
            return Result.fail("AI 服务未启用，请在后端启动配置中设置 DEEPSEEK_API_KEY，并开启 Spring AI Chat。");
        }

        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        try {
            String content = chatClient.prompt()
                    .user(buildRecommendPrompt(shop))
                    .call()
                    .content();
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
    private String buildRecommendPrompt(Shop shop) {
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
