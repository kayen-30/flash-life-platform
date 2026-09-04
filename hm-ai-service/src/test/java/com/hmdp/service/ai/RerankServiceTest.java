package com.hmdp.service.ai;

import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RerankServiceTest {

    @Test
    void shouldReturnCandidatesInScoringOrder() {
        ScoringModel scoringModel = (segments, query) -> Response.from(List.of(0.12, 0.91, 0.44));
        RerankService rerankService = new RerankService(scoringModel);
        List<String> candidates = List.of(
                "平台规则：用户可以领取优惠券",
                "店铺营业时间：周一到周日 10:00-22:00",
                "优惠券使用：结算时自动抵扣"
        );

        assertThat(rerankService.rerank("平台有什么优惠活动？", candidates, 2))
                .containsExactly(candidates.get(1), candidates.get(2));
    }

    @Test
    void shouldKeepOriginalOrderWhenScoringFails() {
        ScoringModel scoringModel = (segments, query) -> {
            throw new IllegalStateException("scoring unavailable");
        };
        RerankService rerankService = new RerankService(scoringModel);
        List<String> candidates = List.of("第一条", "第二条", "第三条");

        assertThat(rerankService.rerank("查询", candidates, 2))
                .containsExactly(candidates.get(0), candidates.get(1));
    }
}
