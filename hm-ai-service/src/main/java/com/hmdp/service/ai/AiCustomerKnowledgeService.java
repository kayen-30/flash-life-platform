package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class AiCustomerKnowledgeService {

    private final EmbeddingModel embeddingModel;
    private final ClasspathKnowledgeResourceLoader knowledgeResourceLoader;
    private final Object vectorInitMonitor = new Object();
    private final Set<String> initializedVectorIndexes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean vectorUnavailableLogged = new AtomicBoolean(false);
    private final AtomicBoolean embeddingUnavailableLogged = new AtomicBoolean(false);
    private LettuceConnectionFactory vectorConnectionFactory;
    private StringRedisTemplate vectorRedisTemplate;

    @Value("${hmdp.ai.rag.vector.enabled:false}")
    private boolean vectorEnabled;

    @Value("${hmdp.ai.rag.vector.index-name:idx:ai:knowledge}")
    private String baseIndexName;

    @Value("${hmdp.ai.rag.vector.key-prefix:ai:knowledge:}")
    private String baseKeyPrefix;

    @Value("${hmdp.ai.rag.vector.top-k:3}")
    private int topK;

    @Value("${hmdp.ai.rag.vector.max-distance:0.8}")
    private double vectorMaxDistance;

    @Value("${hmdp.ai.rag.vector.redis.host:127.0.0.1}")
    private String vectorRedisHost;

    @Value("${hmdp.ai.rag.vector.redis.port:6380}")
    private int vectorRedisPort;

    @Value("${hmdp.ai.rag.vector.redis.database:0}")
    private int vectorRedisDatabase;

    @Value("${hmdp.ai.rag.vector.redis.password:}")
    private String vectorRedisPassword;

    public AiCustomerKnowledgeService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                      ClasspathKnowledgeResourceLoader knowledgeResourceLoader) {
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
        this.knowledgeResourceLoader = knowledgeResourceLoader;
    }

    /**
     * 保持旧调用方兼容，只返回可注入提示词的知识上下文。
     */
    public String retrieve(String question, Long shopId) {
        return retrieveWithSources(question, shopId).context();
    }

    /**
     * 返回检索上下文及其可追溯来源，供接口层或观测链路使用。
     */
    public AiKnowledgeRetrievalResult retrieveWithSources(String question, Long shopId) {
        KnowledgeCatalog catalog = knowledgeResourceLoader.load();
        if (catalog.documents().isEmpty()) {
            return emptyResult(shopId);
        }

        List<RankedKnowledge> vectorHits = retrieveByVector(question, catalog);
        if (!vectorHits.isEmpty()) {
            return toResult(shopId, catalog.version(), vectorHits, AiKnowledgeRetrievalResult.RetrievalMode.VECTOR);
        }

        List<RankedKnowledge> keywordHits = retrieveByKeyword(question, catalog.documents());
        if (!keywordHits.isEmpty()) {
            return toResult(shopId, catalog.version(), keywordHits, AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);
        }
        return emptyResult(shopId);
    }

    private List<RankedKnowledge> retrieveByVector(String question, KnowledgeCatalog catalog) {
        if (!vectorEnabled || StrUtil.isBlank(question)) {
            return List.of();
        }
        if (!hasEmbeddingModel()) {
            if (embeddingUnavailableLogged.compareAndSet(false, true)) {
                log.info("未配置 EmbeddingModel，知识检索将使用关键词兜底");
            }
            return List.of();
        }
        try {
            float[] queryVector = embed(question);
            VectorNamespace namespace = vectorNamespace(catalog.version(), queryVector.length);
            initializeVectorIndex(namespace, catalog.documents());

            List<RankedKnowledge> hits = new ArrayList<>();
            for (VectorKnowledgeHit hit : searchVector(namespace, queryVector)) {
                KnowledgeDocument document = catalog.documentById(hit.documentId());
                if (document == null || !isRelevantVectorHit(hit)) {
                    continue;
                }
                hits.add(new RankedKnowledge(document, vectorRelevance(hit.distance())));
            }
            return hits;
        } catch (RuntimeException e) {
            if (vectorUnavailableLogged.compareAndSet(false, true)) {
                // Redis Stack 或向量模型异常不应影响客服主链路。
                log.warn("Redis Stack 向量检索不可用，已退回关键词检索", e);
            }
            return List.of();
        }
    }

    private void initializeVectorIndex(VectorNamespace namespace, List<KnowledgeDocument> documents) {
        if (initializedVectorIndexes.contains(namespace.indexName())) {
            return;
        }
        synchronized (vectorInitMonitor) {
            if (initializedVectorIndexes.contains(namespace.indexName())) {
                return;
            }
            createIndex(namespace);
            for (KnowledgeDocument document : documents) {
                float[] vector = embed(document.searchText());
                if (vector.length != namespace.dimensions()) {
                    throw new IllegalStateException("EmbeddingModel 返回的向量维度不一致");
                }
                saveKnowledgeVector(namespace, document, vector);
            }
            // 仅在索引与全部文档成功写入后标记，失败时允许下次请求重试。
            initializedVectorIndexes.add(namespace.indexName());
        }
    }

    private void createIndex(VectorNamespace namespace) {
        try {
            execute(
                    "FT.CREATE",
                    bytes(namespace.indexName()),
                    bytes("ON"),
                    bytes("HASH"),
                    bytes("PREFIX"),
                    bytes("1"),
                    bytes(namespace.keyPrefix()),
                    bytes("SCHEMA"),
                    bytes("id"),
                    bytes("TAG"),
                    bytes("title"),
                    bytes("TEXT"),
                    bytes("content"),
                    bytes("TEXT"),
                    bytes("source"),
                    bytes("TAG"),
                    bytes("vector"),
                    bytes("VECTOR"),
                    bytes("HNSW"),
                    bytes("6"),
                    bytes("TYPE"),
                    bytes("FLOAT32"),
                    bytes("DIM"),
                    bytes(String.valueOf(namespace.dimensions())),
                    bytes("DISTANCE_METRIC"),
                    bytes("COSINE")
            );
        } catch (RuntimeException e) {
            if (!isIndexAlreadyExists(e)) {
                throw e;
            }
        }
    }

    private void saveKnowledgeVector(VectorNamespace namespace, KnowledgeDocument document, float[] vector) {
        execute(
                "HSET",
                bytes(namespace.keyPrefix() + document.id()),
                bytes("id"),
                bytes(document.id()),
                bytes("title"),
                bytes(document.title()),
                bytes("content"),
                bytes(document.content()),
                bytes("source"),
                bytes(document.source()),
                bytes("vector"),
                vectorBytes(vector)
        );
    }

    private List<VectorKnowledgeHit> searchVector(VectorNamespace namespace, float[] queryVector) {
        Object result = executeSearch(
                "FT.SEARCH",
                bytes(namespace.indexName()),
                bytes("*=>[KNN " + Math.max(1, topK) + " @vector $vec AS distance]"),
                bytes("PARAMS"),
                bytes("2"),
                bytes("vec"),
                vectorBytes(queryVector),
                bytes("RETURN"),
                bytes("2"),
                bytes("id"),
                bytes("distance"),
                bytes("SORTBY"),
                bytes("distance"),
                bytes("DIALECT"),
                bytes("2")
        );
        return parseSearchHits(result);
    }

    private Object execute(String command, byte[]... args) {
        return getVectorRedisTemplate().execute(connection -> connection.execute(command, args), true);
    }

    private Object executeSearch(String command, byte[]... args) {
        try (RedisConnection connection = getVectorRedisTemplate().getConnectionFactory().getConnection()) {
            if (connection instanceof LettuceConnection lettuceConnection) {
                // RediSearch 返回嵌套数组，使用 Lettuce 原生输出保留字段结构。
                return lettuceConnection.execute(command, new ArrayOutput<>(ByteArrayCodec.INSTANCE), args);
            }
            return connection.execute(command, args);
        }
    }

    private StringRedisTemplate getVectorRedisTemplate() {
        if (vectorRedisTemplate != null) {
            return vectorRedisTemplate;
        }
        synchronized (vectorInitMonitor) {
            if (vectorRedisTemplate != null) {
                return vectorRedisTemplate;
            }
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(vectorRedisHost, vectorRedisPort);
            configuration.setDatabase(vectorRedisDatabase);
            if (StrUtil.isNotBlank(vectorRedisPassword)) {
                configuration.setPassword(vectorRedisPassword);
            }
            // RAG 使用独立 Redis Stack，避免影响业务 Redis 数据。
            vectorConnectionFactory = new LettuceConnectionFactory(configuration);
            vectorConnectionFactory.afterPropertiesSet();
            vectorRedisTemplate = new StringRedisTemplate(vectorConnectionFactory);
            vectorRedisTemplate.afterPropertiesSet();
            return vectorRedisTemplate;
        }
    }

    @PreDestroy
    public void destroy() {
        if (vectorConnectionFactory != null) {
            vectorConnectionFactory.destroy();
        }
    }

    private List<VectorKnowledgeHit> parseSearchHits(Object result) {
        List<VectorKnowledgeHit> hits = new ArrayList<>();
        if (!(result instanceof List<?> rows)) {
            return hits;
        }
        for (int i = 2; i < rows.size(); i += 2) {
            Object fields = rows.get(i);
            if (!(fields instanceof List<?> fieldList)) {
                continue;
            }
            String documentId = null;
            Double distance = null;
            for (int j = 0; j + 1 < fieldList.size(); j += 2) {
                String fieldName = toText(fieldList.get(j));
                String fieldValue = toText(fieldList.get(j + 1));
                if ("id".equals(fieldName)) {
                    documentId = fieldValue;
                } else if ("distance".equals(fieldName)) {
                    distance = parseDistance(fieldValue);
                }
            }
            if (StrUtil.isNotBlank(documentId) && distance != null) {
                hits.add(new VectorKnowledgeHit(documentId, distance));
            }
        }
        return hits;
    }

    private boolean isRelevantVectorHit(VectorKnowledgeHit hit) {
        // RediSearch 的 COSINE 返回距离，数值越小表示语义越接近。
        return hit.distance() <= vectorMaxDistance;
    }

    private Double parseDistance(String value) {
        try {
            return StrUtil.isBlank(value) ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
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
                .limit(Math.max(1, topK))
                .toList();
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

    private VectorNamespace vectorNamespace(String knowledgeVersion, int dimensions) {
        String version = safeRedisSegment(knowledgeVersion);
        String indexPrefix = StrUtil.removeSuffix(StrUtil.blankToDefault(baseIndexName, "idx:ai:knowledge"), ":");
        String keyPrefix = StrUtil.addSuffixIfNot(StrUtil.blankToDefault(baseKeyPrefix, "ai:knowledge:"), ":");
        String suffix = "v:" + version + ":d:" + dimensions;
        // 版本与维度进入命名空间，更新资源或更换模型时不会复用旧 schema。
        return new VectorNamespace(indexPrefix + ":" + suffix, keyPrefix + suffix + ":", dimensions);
    }

    private String safeRedisSegment(String value) {
        String normalized = StrUtil.blankToDefault(value, "unknown").replaceAll("[^A-Za-z0-9._-]", "-");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private boolean isIndexAlreadyExists(RuntimeException e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("index already exists")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasEmbeddingModel() {
        return embeddingModel != null;
    }

    private float[] embed(String text) {
        float[] vector = embeddingModel.embed(text);
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("EmbeddingModel 未返回有效向量");
        }
        return vector;
    }

    private double vectorRelevance(Double distance) {
        return Math.max(0D, Math.min(1D, 1D - distance));
    }

    private byte[] vectorBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String toText(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? "" : value.toString();
    }

    private record VectorNamespace(String indexName, String keyPrefix, int dimensions) {
    }

    private record VectorKnowledgeHit(String documentId, Double distance) {
    }

    private record RankedKnowledge(KnowledgeDocument document, double relevance) {
    }
}
