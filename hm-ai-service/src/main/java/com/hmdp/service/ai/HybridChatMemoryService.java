package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合会话记忆：普通 Redis 保存最近对话，Milvus 保存可按语义检索的历史问答。
 */
@Slf4j
@Service
public class HybridChatMemoryService {

    private static final int DEFAULT_SHORT_TERM_ROUNDS = 3;
    private static final int DEFAULT_LONG_TERM_CANDIDATES = 5;
    private static final int DEFAULT_LONG_TERM_RESULTS = 2;
    private static final int MAX_STORED_CODE_POINTS = 1800;

    private final EmbeddingModel embeddingModel;
    private final ObjectProvider<EmbeddingStore<TextSegment>> conversationStoreProvider;
    private final AiConversationService conversationService;
    private final AiPrivacySanitizer privacySanitizer;
    private final AiExecutionGuard executionGuard;

    @Value("${hmdp.ai.rag.memory.short-term-rounds:3}")
    private int shortTermRounds;

    @Value("${hmdp.ai.rag.memory.long-term-candidate-top-k:5}")
    private int longTermCandidateTopK;

    @Value("${hmdp.ai.rag.memory.long-term-top-k:2}")
    private int longTermTopK;

    @Value("${hmdp.ai.rag.memory.min-score:0.5}")
    private double minScore;

    public HybridChatMemoryService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                   @Qualifier("conversationEmbeddingStore")
                                   ObjectProvider<EmbeddingStore<TextSegment>> conversationStoreProvider,
                                   AiConversationService conversationService,
                                   AiPrivacySanitizer privacySanitizer,
                                   AiExecutionGuard executionGuard) {
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
        this.conversationStoreProvider = conversationStoreProvider;
        this.conversationService = conversationService;
        this.privacySanitizer = privacySanitizer;
        this.executionGuard = executionGuard;
    }

    public boolean isLongTermMemoryEnabled() {
        return embeddingModel != null;
    }

    /** 先取 Redis 最近消息，再按当前问题从 Milvus 追加用户自己的历史问答。 */
    public List<AiConversationService.ConversationMessage> getHybridMemory(
            Long userId, String conversationId, String currentQuery) {
        List<AiConversationService.ConversationMessage> memory = new ArrayList<>(
                conversationService.getRecentMessages(
                        userId, conversationId, positiveOrDefault(shortTermRounds, DEFAULT_SHORT_TERM_ROUNDS)));
        if (embeddingModel == null || StrUtil.isBlank(currentQuery)) {
            return List.copyOf(memory);
        }

        try {
            EmbeddingStore<TextSegment> conversationStore = conversationStoreProvider.getIfAvailable();
            if (conversationStore == null) {
                return List.copyOf(memory);
            }
            Embedding queryEmbedding = embed(currentQuery);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(positiveOrDefault(longTermCandidateTopK, DEFAULT_LONG_TERM_CANDIDATES))
                    .minScore(minScore)
                    // 在 Milvus 端先按用户过滤，避免全库召回后再泄漏到其他用户上下文。
                    .filter(new IsEqualTo("userId", userId.toString()))
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = executionGuard.execute(
                    "memory", () -> conversationStore.search(request).matches());
            int maxResults = positiveOrDefault(longTermTopK, DEFAULT_LONG_TERM_RESULTS);
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (memory.size() >= positiveOrDefault(shortTermRounds, DEFAULT_SHORT_TERM_ROUNDS) * 2 + maxResults) {
                    break;
                }
                AiConversationService.ConversationMessage parsed = parseStoredConversation(match);
                if (parsed != null) {
                    memory.add(parsed);
                }
            }
        } catch (RuntimeException exception) {
            // 长期记忆是增强项，Milvus 异常时保留 Redis 短期记忆即可继续回答。
            log.warn("Milvus 长期会话记忆不可用，已保留 Redis 短期记忆，userId={}", userId, exception);
        }
        return List.copyOf(memory);
    }

    /** 异步保存问答，避免长期记忆写入增加主请求延迟。 */
    @Async("aiMemoryExecutor")
    public void storeConversationAsync(Long userId, String conversationId,
                                       String question, String answer, Long shopId) {
        if (embeddingModel == null || userId == null) {
            return;
        }
        try {
            String cleanQuestion = privacySanitizer.limit(
                    privacySanitizer.sanitizeForModel(question), MAX_STORED_CODE_POINTS / 2);
            String cleanAnswer = privacySanitizer.limit(
                    privacySanitizer.sanitizeForModel(answer), MAX_STORED_CODE_POINTS / 2);
            if (StrUtil.isBlank(cleanQuestion) || StrUtil.isBlank(cleanAnswer)) {
                return;
            }
            String text = "Q: " + cleanQuestion + "\nA: " + cleanAnswer;
            Map<String, String> values = new LinkedHashMap<>();
            values.put("userId", userId.toString());
            values.put("conversationId", conversationId);
            values.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
            values.put("type", "conversation");
            values.put("source", "历史对话");
            if (shopId != null) {
                values.put("shopId", shopId.toString());
            }
            EmbeddingStore<TextSegment> conversationStore = conversationStoreProvider.getIfAvailable();
            if (conversationStore == null) {
                return;
            }
            Embedding embedding = embed(text);
            executionGuard.execute("memory", () -> {
                conversationStore.add(embedding, TextSegment.from(text, Metadata.from(values)));
                return null;
            });
            log.info("对话已存入 Milvus 长期记忆，userId={}, textLength={}", userId, text.length());
        } catch (RuntimeException exception) {
            log.warn("Milvus 长期会话记忆写入失败，userId={}", userId, exception);
        }
    }

    private AiConversationService.ConversationMessage parseStoredConversation(
            EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null) {
            return null;
        }
        String text = match.embedded().text();
        int answerMarker = text == null ? -1 : text.indexOf("\nA:");
        if (answerMarker < 0 || !text.startsWith("Q: ")) {
            return null;
        }
        String question = text.substring(3, answerMarker).trim();
        String answer = text.substring(answerMarker + 3).trim();
        if (question.isBlank() || answer.isBlank()) {
            return null;
        }
        String timestamp = match.embedded().metadata().getString("timestamp");
        String date = timestamp != null && timestamp.length() >= 10 ? timestamp.substring(0, 10) : "更早";
        return new AiConversationService.ConversationMessage(
                "assistant", "[历史对话 " + date + "] 用户问：" + question + "；客服答：" + answer);
    }

    private Embedding embed(String text) {
        var response = executionGuard.execute("embedding", () -> embeddingModel.embed(text));
        Embedding embedding = response == null ? null : response.content();
        if (embedding == null || embedding.vector() == null || embedding.vector().length == 0) {
            throw new IllegalStateException("EmbeddingModel 未返回有效向量");
        }
        return embedding;
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }
}
