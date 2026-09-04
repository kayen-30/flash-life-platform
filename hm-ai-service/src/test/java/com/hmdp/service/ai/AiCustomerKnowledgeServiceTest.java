package com.hmdp.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AiCustomerKnowledgeServiceTest {

    @Test
    void shouldLoadFriendlySourceLabelsFromKnowledgeResource() {
        ClasspathKnowledgeResourceLoader loader = new ClasspathKnowledgeResourceLoader(
                new DefaultResourceLoader(), new ObjectMapper());
        ReflectionTestUtils.setField(loader, "resourceLocation", "classpath:knowledge/customer-service-v1.json");

        KnowledgeCatalog catalog = loader.load();

        assertThat(catalog.version()).isEqualTo("v3");
        assertThat(catalog.documents()).extracting(KnowledgeDocument::source)
                .containsExactly(
                        "平台 FAQ - 秒杀下单规则",
                        "平台 FAQ - 登录和令牌",
                        "平台 FAQ - 店铺详情",
                        "平台 FAQ - 优惠券说明",
                        "平台 FAQ - 探店笔记");
    }
}
