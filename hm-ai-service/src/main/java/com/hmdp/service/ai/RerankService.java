package com.hmdp.service.ai;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * 对向量召回候选做精排；未启用 Cohere 或外部精排失败时保留召回顺序，确保 RAG 可用。
 */
@Slf4j
@Service
public class RerankService {

    private final ScoringModel scoringModel;
    private final AiExecutionGuard executionGuard;

    @Autowired
    public RerankService(ObjectProvider<ScoringModel> scoringModelProvider,
                         AiExecutionGuard executionGuard) {
        this.scoringModel = scoringModelProvider.getIfAvailable();
        this.executionGuard = executionGuard;
    }

    RerankService(ScoringModel scoringModel) {
        this.scoringModel = scoringModel;
        this.executionGuard = null;
    }

    public boolean isExternalModelEnabled() {
        return scoringModel != null;
    }

    /**
     * 兼容直接按文本精排的调用方。
     */
    public List<String> rerank(String query, List<String> candidates, int topK) {
        return rerank(query, candidates, Function.identity(), topK);
    }

    /**
     * 保留候选对象本身，避免精排后知识来源与正文错位。
     */
    public <T> List<T> rerank(String query, List<T> candidates,
                              Function<T, String> textExtractor, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        Objects.requireNonNull(textExtractor, "textExtractor 不能为空");

        int resultSize = Math.min(topK, candidates.size());
        if (scoringModel == null) {
            return candidates.subList(0, resultSize);
        }

        try {
            List<TextSegment> segments = candidates.stream()
                    .map(textExtractor)
                    .map(TextSegment::from)
                    .toList();
            Response<List<Double>> response = executionGuard == null
                    ? scoringModel.scoreAll(segments, query)
                    : executionGuard.execute("rerank", () -> scoringModel.scoreAll(segments, query));
            List<Double> scores = response == null ? null : response.content();
            validateScores(scores, candidates.size());

            return IntStream.range(0, candidates.size())
                    .boxed()
                    .sorted(Comparator.comparingDouble((Integer index) -> scores.get(index)).reversed())
                    .limit(resultSize)
                    .map(candidates::get)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("Rerank 失败，已使用原始召回顺序，candidateCount={}", candidates.size(), exception);
            return candidates.subList(0, resultSize);
        }
    }

    private void validateScores(List<Double> scores, int expectedSize) {
        if (scores == null || scores.size() != expectedSize
                || scores.stream().anyMatch(score -> score == null || !Double.isFinite(score))) {
            throw new IllegalStateException("Rerank 返回的分数数量或内容无效");
        }
    }
}
