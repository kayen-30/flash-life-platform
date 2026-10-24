package com.hmdp.ai.knowledge;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.execution.AiExecutionGuard;
import com.hmdp.ai.execution.AiDeadlineExceededException;
import com.hmdp.dto.UserDTO;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;
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
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.logical.And;

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
    private final KnowledgeIndexState indexState;
    private final KnowledgePrincipal knowledgePrincipal;
    private Clock clock = Clock.systemUTC();

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
                                      AiExecutionGuard executionGuard,
                                      KnowledgeIndexState indexState,
                                      KnowledgePrincipal knowledgePrincipal) {
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
        this.knowledgeStoreProvider = knowledgeStoreProvider;
        this.knowledgeResourceLoader = knowledgeResourceLoader;
        this.rerankService = rerankService;
        this.executionGuard = executionGuard;
        this.indexState = indexState;
        this.knowledgePrincipal = knowledgePrincipal;
    }

    /** 保持旧调用方兼容，只返回可注入提示词的知识上下文。 */
    public String retrieve(String question, Long shopId) {
        return retrieveWithSources(question, shopId).context();
    }

    /** 返回检索上下文及其可追溯来源，Milvus 异常时交给客服入口执行降级。 */
    public AiKnowledgeRetrievalResult retrieveWithSources(String question, Long shopId) {
        if (StrUtil.isBlank(question)) return emptyResult(shopId);
        KnowledgeCatalog catalog = knowledgeResourceLoader.load();
        UserDTO principal = currentPrincipal(catalog);
        Instant retrievedAt = clock.instant();
        List<KnowledgeDocument> readable = catalog.documents().stream()
                .filter(document -> document.access().canRead(principal) && document.appliesAt(retrievedAt, shopId)).toList();
        if (readable.isEmpty()) return emptyResult(shopId);

        // 向量检索保留语义改写能力，不能先用关键词硬拦截；泛词限制只用于关键词回退。
        // 召回候选不是用户引用，最终 sources 另由答复实际选用的证据白名单生成。
        boolean unavailable = false;
        List<RankedKnowledge> hits = List.of();
        AiKnowledgeRetrievalResult.RetrievalMode mode = AiKnowledgeRetrievalResult.RetrievalMode.VECTOR;
        try {
            hits = retrieveByVector(question, catalog, readable);
        } catch (AiDeadlineExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            unavailable = true;
            log.warn("向量检索故障，使用受业务边界约束的关键词回退");
        }
        if (hits.isEmpty()) {
            mode = AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD;
            hits = retrieveByKeyword(question, readable);
        }
        // 外部重排看到的也只能是当前有权读取的发布内容，不能在重排后才做权限过滤。
        if (!catalogIsCurrent(catalog)) return unavailableResult(shopId);
        UserDTO currentPrincipal = currentPrincipal(catalog);
        Instant beforeRerank = clock.instant();
        hits = hits.stream().filter(hit -> hit.document().access().canRead(currentPrincipal)
                && hit.document().appliesAt(beforeRerank, shopId)).toList();
        AiKnowledgeRetrievalResult result = hits.isEmpty() ? emptyResult(shopId)
                : toResult(shopId, catalog.indexVersion(), rerank(question, hits, mode), mode);
        if (!isCurrentAndReadable(result, result.sources().stream().map(AiKnowledgeRetrievalResult.Source::documentId).toList())) {
            return unavailableResult(shopId);
        }
        return new AiKnowledgeRetrievalResult(result.context(), result.sources(), result.retrievalMode(), unavailable, shopId);
    }

    /** 每次构建使用独立批次；只有当前发布且完整写入，协调器才能启用这个批次。 */
    public boolean importKnowledge(String publication, String build) {
        KnowledgeCatalog catalog = knowledgeResourceLoader.load();
        if (!catalog.indexVersion().equals(publication)) return false;
        if (catalog.documents().isEmpty()) return true;
        // 将未来生效资料也完整建索引，查询时过滤；跨生效边界不依赖重建或后台定时任务。
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
                    values.put("knowledgeVersion", catalog.indexVersion());
                    values.put("documentVersion", document.version());
                    values.put("indexBuild", build);
                    return values;
                })
                .toList();
        int stored = addKnowledgeBatch(texts, metadata);
        if (stored != texts.size() || !catalogIsCurrent(catalog)) {
            log.warn("Milvus 知识导入未完成，expected={}, stored={}", texts.size(), stored);
            return false;
        }
        log.info("Milvus 知识导入完成，version={}, count={}", catalog.version(), stored);
        return true;
    }

    /** 只清理已明确未发布的本次批次，保留已就绪和提交状态不明的数据。 */
    void discardBuild(String publication, String build) {
        try {
            var store = knowledgeStore();
            if (store != null) executionGuard.execute("rag-index", () -> {
                store.removeAll(new And(new IsEqualTo("knowledgeVersion", publication), new IsEqualTo("indexBuild", build)));
                return null;
            });
        } catch (RuntimeException failure) {
            log.warn("AI_KNOWLEDGE_INDEX phase=CLEANUP_DEFERRED, causeType={}", failure.getClass().getSimpleName());
        }
    }

    public String knowledgeVersion() {
        return knowledgeResourceLoader.load().indexVersion();
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
            log.warn("添加知识到 Milvus 失败，文本长度={}, causeType={}", text.length(), exception.getClass().getSimpleName());
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
            return executionGuard.execute("rag-index", () -> {
                List<String> storedIds = knowledgeStore().addAll(embeddings, segments);
                // 确认校验也在调用许可内，部分写入不能被记作成功并关闭熔断。
                if (storedIds == null || storedIds.size() != segments.size()
                        || storedIds.stream().anyMatch(StrUtil::isBlank)
                        || storedIds.stream().distinct().count() != segments.size()) {
                    throw new IllegalStateException("知识索引写入确认不完整");
                }
                return segments.size();
            });
        } catch (RuntimeException exception) {
            log.warn("批量添加知识到 Milvus 失败，数量={}, causeType={}", texts.size(), exception.getClass().getSimpleName());
            return 0;
        }
    }

    private List<RankedKnowledge> retrieveByVector(String question, KnowledgeCatalog catalog, List<KnowledgeDocument> readable) {
        if (!vectorEnabled || StrUtil.isBlank(question)) return List.of();
        String build = indexState.readyBuild(catalog.indexVersion());
        // 就绪指针缺失时不能查询部分写入的数据，仍可用当前授权正文做关键词回退。
        if (build == null) return List.of();
        if (embeddingModel == null || knowledgeStore() == null) {
            throw new IllegalStateException("向量检索已启用但模型或存储未就绪");
        }
        try {
            Embedding queryEmbedding = embed(question);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(candidateLimit())
                    .minScore(minScore)
                    .filter(new And(new And(new IsEqualTo("knowledgeVersion", catalog.indexVersion()),
                                    new IsEqualTo("indexBuild", build)),
                            new IsIn("documentId", readable.stream().map(KnowledgeDocument::id).toList())))
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = executionGuard.execute(
                    "rag", () -> knowledgeStore().search(request).matches());
            Map<String, RankedKnowledge> hits = new LinkedHashMap<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (match == null || match.embedded() == null || match.score() == null
                        || !Double.isFinite(match.score()) || match.score() < minScore) {
                    continue;
                }
                // 索引的searchText含标题，只负责召回；展示证据必须取当前发布版本的权威原文。
                KnowledgeDocument document = catalog.documentById(match.embedded().metadata().getString("documentId"));
                // 即使索引忽略过滤条件或返回伪造标签，也不能越权、混用旧版本或用索引正文回答。
                if (document != null && readable.contains(document)
                        && catalog.indexVersion().equals(match.embedded().metadata().getString("knowledgeVersion"))
                        && build.equals(match.embedded().metadata().getString("indexBuild"))
                        && document.version().equals(match.embedded().metadata().getString("documentVersion"))) {
                    hits.merge(document.id(), new RankedKnowledge(document, match.score()),
                            (first, duplicate) -> first.relevance() >= duplicate.relevance() ? first : duplicate);
                }
            }
            return List.copyOf(hits.values());
        } catch (RuntimeException exception) {
            // Milvus 或 Embedding 异常只影响知识上下文，调用入口仍可继续使用实时工具。
            throw exception;
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
        var budget = com.hmdp.ai.execution.AiExecutionBudget.current();
        log.info("AI_KNOWLEDGE_RETRIEVAL requestId={}, candidates={}, selected={}, mode={}",
                budget == null ? "none" : budget.getRequestId(), candidates.size(), reranked.size(), mode);
        return reranked;
    }

    private int keywordScore(String question, KnowledgeDocument document) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        int score = normalizedQuestion.contains(document.title().toLowerCase(Locale.ROOT)) ? 2 : 0;
        for (String keyword : document.keywords()) {
            if (Set.of("推荐", "评价", "评论", "热门", "笔记", "券", "优惠").contains(keyword)) continue;
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
            context.append("- [documentId=").append(document.id()).append(" | ").append(document.title())
                    .append(" | 来源: ").append(document.source()).append("]\n")
                    .append(document.content()).append('\n');
            sources.add(new AiKnowledgeRetrievalResult.Source(
                    document.id(), document.title(), document.source(), knowledgeVersion, hit.relevance(), document.content(), document.version()));
        }
        return new AiKnowledgeRetrievalResult(context.toString(), sources, mode, false, shopId);
    }

    /** 生成前与返回前都重新核对发布指纹和当前身份，旧引用或权限撤销不能沿用已取出的正文。 */
    public boolean isCurrentAndReadable(AiKnowledgeRetrievalResult snapshot, List<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) return true;
        if (snapshot == null) return false;
        try {
            KnowledgeCatalog current = knowledgeResourceLoader.load();
            UserDTO principal = currentPrincipal(current);
            Instant checkedAt = clock.instant();
            for (String id : selectedIds) {
                AiKnowledgeRetrievalResult.Source source = snapshot.sources().stream()
                        .filter(value -> value.documentId().equals(id)).findFirst().orElse(null);
                KnowledgeDocument document = current.documentById(id);
                if (source == null || document == null || !document.access().canRead(principal)
                        || !document.appliesAt(checkedAt, snapshot.shopId())
                        || !current.indexVersion().equals(source.knowledgeVersion())
                        || !document.version().equals(source.documentVersion()) || !document.content().equals(source.content())
                        || !document.title().equals(source.title()) || !document.source().equals(source.source())) return false;
            }
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean catalogIsCurrent(KnowledgeCatalog snapshot) {
        try { return knowledgeResourceLoader.load().indexVersion().equals(snapshot.indexVersion()); }
        catch (RuntimeException unavailable) { return false; }
    }

    /** 纯公开知识无需读取登录态；混合清单中的受限授权每个外发/提交边界重新确认。 */
    private UserDTO currentPrincipal(KnowledgeCatalog catalog) {
        return catalog.documents().stream().anyMatch(document ->
                document.access().visibility() == KnowledgeAccess.Visibility.RESTRICTED)
                ? knowledgePrincipal.currentUser() : null;
    }

    private AiKnowledgeRetrievalResult unavailableResult(Long shopId) {
        var empty = emptyResult(shopId);
        return new AiKnowledgeRetrievalResult(empty.context(), empty.sources(), empty.retrievalMode(), true, shopId);
    }

    private AiKnowledgeRetrievalResult emptyResult(Long shopId) {
        StringBuilder context = baseContext(shopId);
        context.append("- 当前知识库没有检索到可确认的依据。回答应围绕店铺、优惠券、秒杀、登录和探店笔记等平台业务；无法确认时请引导用户补充具体信息。\n");
        return new AiKnowledgeRetrievalResult(context.toString(), List.of(), AiKnowledgeRetrievalResult.RetrievalMode.EMPTY, false, shopId);
    }

    private StringBuilder baseContext(Long shopId) {
        StringBuilder context = new StringBuilder();
        if (shopId != null) {
            context.append("当前用户正在浏览店铺，shopId=").append(shopId).append("。\n");
        }
        return context;
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
        return executionGuard.execute("embedding-index", () -> {
            var response = embeddingModel.embedAll(segments);
            List<Embedding> embeddings = response == null ? null : response.content();
            if (embeddings == null || embeddings.size() != segments.size()
                    || embeddings.stream().anyMatch(value -> value == null || value.vector().length == 0)) {
                throw new IllegalStateException("EmbeddingModel 未返回完整批量向量");
            }
            return embeddings;
        });
    }

    private int candidateLimit() {
        return Math.max(Math.max(1, candidateTopK), Math.max(1, topK));
    }

    private record RankedKnowledge(KnowledgeDocument document, double relevance) {
    }
}
