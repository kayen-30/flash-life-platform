package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class AiCustomerKnowledgeService {

    private static final int LOCAL_EMBEDDING_DIMENSIONS = 384;

    private static final List<KnowledgeItem> KNOWLEDGE_ITEMS = List.of(
            new KnowledgeItem(
                    "秒杀下单规则",
                    List.of("秒杀", "抢购", "库存", "一人一单", "优惠券"),
                    "秒杀券需要在活动开始后、结束前购买；同一用户对同一张秒杀券只能下一单；库存扣减成功后才会创建订单。"
            ),
            new KnowledgeItem(
                    "登录和令牌",
                    List.of("登录", "验证码", "token", "退出", "授权"),
                    "用户通过手机号验证码登录；登录成功后后端返回 token，前端请求会把 token 放入 authorization 请求头；退出登录会删除 Redis 中的登录态。"
            ),
            new KnowledgeItem(
                    "店铺详情",
                    List.of("店铺", "地址", "营业时间", "评分", "人均", "商圈", "推荐"),
                    "店铺详情包含名称、地址、商圈、人均、评分、销量、评论数、营业时间和优惠券列表，可用于回答探店和到店决策问题。"
            ),
            new KnowledgeItem(
                    "优惠券说明",
                    List.of("代金券", "优惠", "券", "折扣", "使用规则"),
                    "普通代金券展示购买金额、抵扣金额和使用规则；秒杀券额外包含库存、开始时间和结束时间。"
            ),
            new KnowledgeItem(
                    "探店笔记",
                    List.of("笔记", "评价", "评论", "达人", "热门", "点赞"),
                    "探店笔记按点赞数形成热门内容，可结合店铺信息帮助用户了解真实体验。"
            )
    );

    private final EmbeddingModel embeddingModel;
    private final Object vectorInitMonitor = new Object();
    private final AtomicBoolean vectorUnavailableLogged = new AtomicBoolean(false);
    private volatile boolean vectorInitialized = false;
    private LettuceConnectionFactory vectorConnectionFactory;
    private StringRedisTemplate vectorRedisTemplate;

    @Value("${hmdp.ai.rag.vector.enabled:false}")
    private boolean vectorEnabled;

    @Value("${hmdp.ai.rag.vector.local-embedding-enabled:true}")
    private boolean localEmbeddingEnabled;

    @Value("${hmdp.ai.rag.vector.index-name:idx:ai:knowledge}")
    private String indexName;

    @Value("${hmdp.ai.rag.vector.key-prefix:ai:knowledge:}")
    private String keyPrefix;

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

    public AiCustomerKnowledgeService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
    }

    /**
     * 优先使用 Redis Stack 向量召回；未启用或依赖不可用时退回关键词召回。
     */
    public String retrieve(String question, Long shopId) {
        String vectorContext = retrieveByVector(question, shopId);
        if (StrUtil.isNotBlank(vectorContext)) {
            return vectorContext;
        }
        return retrieveByKeyword(question, shopId);
    }

    private String retrieveByVector(String question, Long shopId) {
        if (!vectorEnabled || StrUtil.isBlank(question) || (!hasEmbeddingModel() && !localEmbeddingEnabled)) {
            return null;
        }
        try {
            float[] queryVector = embed(question);
            initializeVectorIndex(queryVector.length);
            List<String> contents = searchVector(queryVector).stream()
                    .filter(hit -> isRelevantVectorHit(question, hit))
                    .map(VectorKnowledgeHit::getContent)
                    .toList();
            if (contents.isEmpty()) {
                return null;
            }
            StringBuilder context = baseContext(shopId);
            contents.forEach(content -> context.append("- ").append(content).append('\n'));
            return context.toString();
        } catch (RuntimeException e) {
            if (vectorUnavailableLogged.compareAndSet(false, true)) {
                log.warn("Redis Stack 向量召回不可用，已退回关键词召回", e);
            }
            return null;
        }
    }

    private void initializeVectorIndex(int dimensions) {
        if (vectorInitialized) {
            return;
        }
        synchronized (vectorInitMonitor) {
            if (vectorInitialized) {
                return;
            }
            // 向量索引和内置知识必须全部写入成功，才标记初始化完成，失败后允许下次重试。
            createIndex(dimensions);
            for (int i = 0; i < KNOWLEDGE_ITEMS.size(); i++) {
                KnowledgeItem item = KNOWLEDGE_ITEMS.get(i);
                float[] vector = embed(item.getSearchText());
                saveKnowledgeVector(i, item, vector);
            }
            vectorInitialized = true;
        }
    }

    private void createIndex(int dimensions) {
        try {
            execute(
                    "FT.CREATE",
                    bytes(indexName),
                    bytes("ON"),
                    bytes("HASH"),
                    bytes("PREFIX"),
                    bytes("1"),
                    bytes(keyPrefix),
                    bytes("SCHEMA"),
                    bytes("title"),
                    bytes("TEXT"),
                    bytes("content"),
                    bytes("TEXT"),
                    bytes("vector"),
                    bytes("VECTOR"),
                    bytes("HNSW"),
                    bytes("6"),
                    bytes("TYPE"),
                    bytes("FLOAT32"),
                    bytes("DIM"),
                    bytes(String.valueOf(dimensions)),
                    bytes("DISTANCE_METRIC"),
                    bytes("COSINE")
            );
        } catch (RuntimeException e) {
            if (!isIndexAlreadyExists(e)) {
                throw e;
            }
        }
    }

    private void saveKnowledgeVector(int index, KnowledgeItem item, float[] vector) {
        execute(
                "HSET",
                bytes(keyPrefix + index),
                bytes("title"),
                bytes(item.getTitle()),
                bytes("content"),
                bytes(item.getContent()),
                bytes("vector"),
                vectorBytes(vector)
        );
    }

    private List<VectorKnowledgeHit> searchVector(float[] queryVector) {
        Object result = executeSearch(
                "FT.SEARCH",
                bytes(indexName),
                bytes("*=>[KNN " + topK + " @vector $vec AS score]"),
                bytes("PARAMS"),
                bytes("2"),
                bytes("vec"),
                vectorBytes(queryVector),
                bytes("RETURN"),
                bytes("3"),
                bytes("title"),
                bytes("content"),
                bytes("score"),
                bytes("SORTBY"),
                bytes("score"),
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
                // RediSearch 返回“总数 + 文档字段”的混合数组，需要 Lettuce 的嵌套数组输出解析。
                return lettuceConnection.execute(command, new ArrayOutput<>(ByteArrayCodec.INSTANCE), args);
            }
            return connection.execute(command, args);
        }
    }

    private StringRedisTemplate getVectorRedisTemplate() {
        if (vectorRedisTemplate != null) {
            return vectorRedisTemplate;
        }
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(vectorRedisHost, vectorRedisPort);
        configuration.setDatabase(vectorRedisDatabase);
        if (StrUtil.isNotBlank(vectorRedisPassword)) {
            configuration.setPassword(vectorRedisPassword);
        }
        // RAG 使用独立 Redis Stack，避免改动业务 Redis 的 6379 数据。
        vectorConnectionFactory = new LettuceConnectionFactory(configuration);
        vectorConnectionFactory.afterPropertiesSet();
        vectorRedisTemplate = new StringRedisTemplate(vectorConnectionFactory);
        vectorRedisTemplate.afterPropertiesSet();
        return vectorRedisTemplate;
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
            String title = null;
            String content = null;
            Double score = null;
            for (int j = 0; j + 1 < fieldList.size(); j += 2) {
                String fieldName = toText(fieldList.get(j));
                String fieldValue = toText(fieldList.get(j + 1));
                if ("title".equals(fieldName)) {
                    title = fieldValue;
                } else if ("content".equals(fieldName)) {
                    content = fieldValue;
                } else if ("score".equals(fieldName)) {
                    score = parseScore(fieldValue);
                }
            }
            if (StrUtil.isNotBlank(content)) {
                hits.add(new VectorKnowledgeHit(title, content, score));
            }
        }
        return hits;
    }

    private boolean isRelevantVectorHit(String question, VectorKnowledgeHit hit) {
        if (hasEmbeddingModel()) {
            // RediSearch COSINE 返回距离，数值越小越相关；超过阈值就交给关键词兜底。
            return hit.getScore() != null && hit.getScore() <= vectorMaxDistance;
        }
        KnowledgeItem item = findKnowledgeItemByTitle(hit.getTitle());
        // 本地哈希向量只用于演示，必须再过业务关键词，避免无关问题也被 KNN 强行命中。
        return item != null && score(question, item) > 0;
    }

    private KnowledgeItem findKnowledgeItemByTitle(String title) {
        if (StrUtil.isBlank(title)) {
            return null;
        }
        for (KnowledgeItem item : KNOWLEDGE_ITEMS) {
            if (title.equals(item.getTitle())) {
                return item;
            }
        }
        return null;
    }

    private Double parseScore(String value) {
        try {
            return StrUtil.isBlank(value) ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // 不同 Redis 客户端可能返回非标准文本，解析失败时不阻断关键词兜底链路。
            return null;
        }
    }

    private String retrieveByKeyword(String question, Long shopId) {
        List<ScoredKnowledge> scoredItems = new ArrayList<>();
        for (KnowledgeItem item : KNOWLEDGE_ITEMS) {
            int score = score(question, item);
            if (score > 0) {
                scoredItems.add(new ScoredKnowledge(item, score));
            }
        }
        scoredItems.sort(Comparator.comparingInt(ScoredKnowledge::getScore).reversed());

        StringBuilder context = baseContext(shopId);
        scoredItems.stream()
                .limit(3)
                .forEach(item -> context.append("- ")
                        .append(item.getItem().getTitle())
                        .append("：")
                        .append(item.getItem().getContent())
                        .append('\n'));
        if (scoredItems.isEmpty()) {
            context.append("- 平台基础规则：回答应围绕店铺、优惠券、秒杀、登录和探店笔记等本地生活业务，不确定时引导用户提供店铺名或问题细节。\n");
        }
        return context.toString();
    }

    private StringBuilder baseContext(Long shopId) {
        StringBuilder context = new StringBuilder();
        if (shopId != null) {
            context.append("当前用户正在浏览店铺，shopId=").append(shopId).append("。\n");
        }
        return context;
    }

    private int score(String question, KnowledgeItem item) {
        if (StrUtil.isBlank(question)) {
            return 0;
        }
        int score = 0;
        for (String keyword : item.getKeywords()) {
            if (question.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private boolean isIndexAlreadyExists(RuntimeException e) {
        // Lettuce 会把 Redis 原始错误包成 RedisSystemException，需要沿异常链识别幂等场景。
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("index already exists")) {
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
        if (hasEmbeddingModel()) {
            return embeddingModel.embed(text);
        }
        // 演示环境常只配置 Chat 模型；本地向量化保证 Redis Stack RAG 可先跑通。
        return localEmbedding(text);
    }

    private float[] localEmbedding(String text) {
        float[] vector = new float[LOCAL_EMBEDDING_DIMENSIONS];
        String normalized = StrUtil.nullToEmpty(text).toLowerCase();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }
            int index = Math.floorMod(current * 31 + i, LOCAL_EMBEDDING_DIMENSIONS);
            vector[index] += 1.0F;
            if (i + 1 < normalized.length()) {
                int bigramIndex = Math.floorMod(current * 131 + normalized.charAt(i + 1), LOCAL_EMBEDDING_DIMENSIONS);
                vector[bigramIndex] += 0.5F;
            }
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            vector[0] = 1.0F;
            return;
        }
        float length = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / length;
        }
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

    private static class KnowledgeItem {
        private final String title;
        private final List<String> keywords;
        private final String content;

        private KnowledgeItem(String title, List<String> keywords, String content) {
            this.title = title;
            this.keywords = keywords;
            this.content = content;
        }

        private String getTitle() {
            return title;
        }

        private List<String> getKeywords() {
            return keywords;
        }

        private String getContent() {
            return content;
        }

        private String getSearchText() {
            return title + "\n" + content;
        }
    }

    private static class ScoredKnowledge {
        private final KnowledgeItem item;
        private final int score;

        private ScoredKnowledge(KnowledgeItem item, int score) {
            this.item = item;
            this.score = score;
        }

        private KnowledgeItem getItem() {
            return item;
        }

        private int getScore() {
            return score;
        }
    }

    private static class VectorKnowledgeHit {
        private final String title;
        private final String content;
        private final Double score;

        private VectorKnowledgeHit(String title, String content, Double score) {
            this.title = title;
            this.content = content;
            this.score = score;
        }

        private String getTitle() {
            return title;
        }

        private String getContent() {
            return content;
        }

        private Double getScore() {
            return score;
        }
    }
}
