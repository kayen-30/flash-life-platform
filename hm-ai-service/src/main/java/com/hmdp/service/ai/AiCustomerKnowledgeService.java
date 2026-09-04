package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 负责平台知识的向量召回、关键词兜底和来源整理；向量数据存储在 Milvus，Redis 不参与向量检索。
 */
@Slf4j
@Service
public class AiCustomerKnowledgeService {

    private final EmbeddingModel embeddingModel;
    private final ObjectProvider<EmbeddingStore<TextSegment>> knowledgeStoreProvider;
    private final ClasspathKnowledgeResourceLoader knowledgeResourceLoader;
    private final RerankService rerankService;
    private final AiExecutionGuard executionGuard;

    @Value("${hmdp.ai.rag.vector.enabled:false}")
    private boolean vectorEnabled;

    @Value("${hmdp.ai.rag.vector.top-k:3}")
    private int topK;

    @Value("${hmdp.ai.rag.vector.candidate-top-k:10}")
    private int candidateTopK;

    @Value("${hmdp.ai.rag.vector.min-score:0.5}")
    private double minScore;

    public AiCustomerKnowledgeService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                      @Qualifier("knowledgeEmbeddingStore")
                                      ObjectProvider<EmbeddingStore<TextSegment>> knowledgeStoreProvider,
                                      ClasspathKnowledgeResourceLoader knowledgeResourceLoader,
                                      RerankService rerankService,
                                      AiExecutionGuard executionGuard) {
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
        this.knowledgeStoreProvider = knowledgeStoreProvider;
        this.knowledgeResourceLoader = knowledgeResourceLoader;
        this.rerankService = rerankService;
        this.executionGuard = executionGuard;
    }

    /** 保持旧调用方兼容，只返回可注入提示词的知识上下文。 */
    public String retrieve(String question, Long shopId) {
        return retrieveWithSources(question, shopId).context();
    }

    /** 返回检索上下文及其可追溯来源，Milvus 异常时交给客服入口执行降级。 */
    public AiKnowledgeRetrievalResult retrieveWithSources(String question, Long shopId) {
        KnowledgeCatalog catalog = knowledgeResourceLoader.load();
        if (catalog.documents().isEmpty()) {
            return emptyResult(shopId);
        }

        List<RankedKnowledge> vectorHits = retrieveByVector(question, catalog.version());
        if (!vectorHits.isEmpty()) {
            return toResult(shopId, catalog.version(), rerank(question, vectorHits,
                    AiKnowledgeRetrievalResult.RetrievalMode.VECTOR),
                    AiKnowledgeRetrievalResult.RetrievalMode.VECTOR);
        }

        List<RankedKnowledge> keywordHits = retrieveByKeyword(question, catalog.documents());
        if (!keywordHits.isEmpty()) {
            return toResult(shopId, catalog.version(), rerank(question, keywordHits,
                    AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD),
                    AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);
        }
        return emptyResult(shopId);
    }

    /** 将类路径知识资源导入 Milvus；批量 Embedding 失败时返回 false，避免写入不完整标记。 */
    public boolean importClasspathKnowledge() {
        KnowledgeCatalog catalog = knowledgeResourceLoader.load();
        if (catalog.documents().isEmpty()) {
            return false;
        }
        List<String> texts = catalog.documents().stream()
                .map(KnowledgeDocument::searchText)
                .toList();
        List<Map<String, String>> metadata = catalog.documents().stream()
                .map(document -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put("documentId", document.id());
                    values.put("title", document.title());
                    values.put("type", "faq");
                    values.put("source", document.source());
                    values.put("knowledgeVersion", catalog.version());
                    return values;
                })
                .toList();
        int stored = addKnowledgeBatch(texts, metadata);
        if (stored != texts.size()) {
            log.warn("Milvus 知识导入未完成，expected={}, stored={}", texts.size(), stored);
            return false;
        }
        log.info("Milvus 知识导入完成，version={}, count={}", catalog.version(), stored);
        return true;
    }

    public String knowledgeVersion() {
        return knowledgeResourceLoader.load().version();
    }

    public boolean isVectorEnabled() {
        return vectorEnabled;
    }

    public boolean requiresExternalModel() {
        return (vectorEnabled && embeddingModel != null) || rerankService.isExternalModelEnabled();
    }

    /** 添加单条知识，metadata 会复制后再补默认来源，兼容 Map.of 等不可变 Map。 */
    public boolean addKnowledge(String text, Map<String, String> metadata) {
        if (!isVectorReady() || StrUtil.isBlank(text)) {
            return false;
        }
        try {
            Metadata segmentMetadata = toMetadata(metadata);
            Embedding embedding = embed(text);
            executionGuard.execute("rag", () -> {
                knowledgeStore().add(embedding, TextSegment.from(text, segmentMetadata));
                return null;
            });
            log.info("添加知识到 Milvus，来源={}, 文本长度={}",
                    segmentMetadata.getString("source"), text.length());
            return true;
        } catch (RuntimeException exception) {
            log.warn("添加知识到 Milvus 失败，文本长度={}", text.length(), exception);
            return false;
        }
    }

    /** 批量写入 FAQ，减少 Embedding 请求和 Milvus RPC 次数。 */
    public int addKnowledgeBatch(List<String> texts, List<Map<String, String>> metadataList) {
        if (!isVectorReady() || texts == null || metadataList == null
                || texts.isEmpty() || texts.size() != metadataList.size()) {
            return 0;
        }
        try {
            List<TextSegment> segments = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                segments.add(TextSegment.from(texts.get(i), toMetadata(metadataList.get(i))));
            }
            List<Embedding> embeddings = embedAll(segments);
            if (embeddings.size() != segments.size()) {
                return 0;
            }
            executionGuard.execute("rag", () -> {
                knowledgeStore().addAll(embeddings, segments);
                return null;
            });
            return segments.size();
        } catch (RuntimeException exception) {
            log.warn("批量添加知识到 Milvus 失败，数量={}", texts.size(), exception);
            return 0;
        }
    }

    private List<RankedKnowledge> retrieveByVector(String question, String knowledgeVersion) {
        if (!isVectorReady() || StrUtil.isBlank(question)) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embed(question);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(candidateLimit())
                    .minScore(minScore)
                    .filter(new IsEqualTo("knowledgeVersion", knowledgeVersion))
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = executionGuard.execute(
                    "rag", () -> knowledgeStore().search(request).matches());
            List<RankedKnowledge> hits = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (match == null || match.embedded() == null || match.score() == null) {
                    continue;
                }
                hits.add(new RankedKnowledge(toDocument(match), match.score()));
            }
            return hits;
        } catch (RuntimeException exception) {
            // Milvus 或 Embedding 异常只影响知识上下文，调用入口仍可继续使用实时工具。
            log.warn("Milvus 向量检索不可用，已退回关键词检索", exception);
            return List.of();
        }
    }

    private List<RankedKnowledge> retrieveByKeyword(String question, List<KnowledgeDocument> documents) {
        if (StrUtil.isBlank(question)) {
            return List.of();
        }
        List<RankedKnowledge> scoredItems = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            int score = keywordScore(question, document);
            if (score > 0) {
                scoredItems.add(new RankedKnowledge(document, score));
            }
        }
        return scoredItems.stream()
                .sorted(Comparator.comparingDouble(RankedKnowledge::relevance).reversed())
                .limit(candidateLimit())
                .toList();
    }

    private List<RankedKnowledge> rerank(String question, List<RankedKnowledge> candidates,
                                          AiKnowledgeRetrievalResult.RetrievalMode mode) {
        List<RankedKnowledge> reranked = rerankService.rerank(
                question, candidates, hit -> hit.document().searchText(), Math.max(1, topK));
        log.info("RAG 召回：候选数={}, Rerank 后={}, mode={}", candidates.size(), reranked.size(), mode);
        return reranked;
    }

    private int keywordScore(String question, KnowledgeDocument document) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        int score = normalizedQuestion.contains(document.title().toLowerCase(Locale.ROOT)) ? 2 : 0;
        for (String keyword : document.keywords()) {
            if (normalizedQuestion.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    private AiKnowledgeRetrievalResult toResult(Long shopId,
                                                 String knowledgeVersion,
                                                 List<RankedKnowledge> hits,
                                                 AiKnowledgeRetrievalResult.RetrievalMode mode) {
        StringBuilder context = baseContext(shopId);
        List<AiKnowledgeRetrievalResult.Source> sources = new ArrayList<>();
        for (RankedKnowledge hit : hits) {
            KnowledgeDocument document = hit.document();
            context.append("- [").append(document.title())
                    .append(" | 来源: ").append(document.source()).append("]\n")
                    .append(document.content()).append('\n');
            sources.add(new AiKnowledgeRetrievalResult.Source(
                    document.id(), document.title(), document.source(), knowledgeVersion, hit.relevance()));
        }
        return new AiKnowledgeRetrievalResult(context.toString(), sources, mode);
    }

    /** 导入当前版本成功后清理所有旧版本，避免失败导入先删除可用知识。 */
    public boolean deleteVersionsExcept(String currentVersion) {
        if (!isVectorReady() || StrUtil.isBlank(currentVersion)) {
            return false;
        }
        try {
            executionGuard.execute("rag", () -> {
                knowledgeStore().removeAll(new IsNotEqualTo("knowledgeVersion", currentVersion));
                return null;
            });
            log.info("Milvus 旧版本知识已清理，保留版本={}", currentVersion);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Milvus 旧版本知识清理失败，保留版本={}", currentVersion, exception);
            return false;
        }
    }

    private AiKnowledgeRetrievalResult emptyResult(Long shopId) {
        StringBuilder context = baseContext(shopId);
        context.append("- 当前知识库没有检索到可确认的依据。回答应围绕店铺、优惠券、秒杀、登录和探店笔记等平台业务；无法确认时请引导用户补充具体信息。\n");
        return new AiKnowledgeRetrievalResult(context.toString(), List.of(), AiKnowledgeRetrievalResult.RetrievalMode.EMPTY);
    }

    private StringBuilder baseContext(Long shopId) {
        StringBuilder context = new StringBuilder();
        if (shopId != null) {
            context.append("当前用户正在浏览店铺，shopId=").append(shopId).append("。\n");
        }
        return context;
    }

    private KnowledgeDocument toDocument(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        String id = StrUtil.blankToDefault(segment.metadata().getString("documentId"), match.embeddingId());
        String title = StrUtil.blankToDefault(segment.metadata().getString("title"), id);
        String source = StrUtil.blankToDefault(segment.metadata().getString("source"), "平台知识库");
        return new KnowledgeDocument(id, title, List.of(), segment.text(), source);
    }

    private Metadata toMetadata(Map<String, String> values) {
        Map<String, String> copy = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        copy.putIfAbsent("source", "平台知识库");
        return Metadata.from(copy);
    }

    private boolean isVectorReady() {
        if (!vectorEnabled || embeddingModel == null) {
            return false;
        }
        try {
            return knowledgeStore() != null;
        } catch (RuntimeException exception) {
            log.warn("Milvus 知识库暂不可用，继续使用关键词检索");
            return false;
        }
    }

    private EmbeddingStore<TextSegment> knowledgeStore() {
        return knowledgeStoreProvider.getIfAvailable();
    }

    private Embedding embed(String text) {
        var response = executionGuard.execute("embedding", () -> embeddingModel.embed(text));
        Embedding embedding = response == null ? null : response.content();
        if (embedding == null || embedding.vector() == null || embedding.vector().length == 0) {
            throw new IllegalStateException("EmbeddingModel 未返回有效向量");
        }
        return embedding;
    }

    private List<Embedding> embedAll(List<TextSegment> segments) {
        var response = executionGuard.execute("embedding", () -> embeddingModel.embedAll(segments));
        List<Embedding> embeddings = response == null ? null : response.content();
        if (embeddings == null) {
            throw new IllegalStateException("EmbeddingModel 未返回批量向量");
        }
        return embeddings;
    }

    private int candidateLimit() {
        return Math.max(Math.max(1, candidateTopK), Math.max(1, topK));
    }

    private record RankedKnowledge(KnowledgeDocument document, double relevance) {
    }
}
