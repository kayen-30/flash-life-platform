package com.hmdp.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Milvus 向量存储配置。普通 Redis 继续承载登录态、限流和短期会话，不再承担向量索引。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MilvusConfig {

    @Value("${milvus.host:127.0.0.1}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.database:default}")
    private String database;

    @Value("${milvus.embedding-dimension:1536}")
    private int dimension;

    @Value("${milvus.knowledge-collection:shop_knowledge}")
    private String knowledgeCollection;

    @Value("${milvus.conversation-collection:conversation_memory}")
    private String conversationCollection;

    @Value("${milvus.auto-flush-on-insert:true}")
    private boolean autoFlushOnInsert;

    private MilvusServiceClient client;

    /** 连接对象延迟创建，Milvus 暂时不可用时不阻断 AI 服务启动。 */
    @Bean
    @Lazy
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port);
        if (database != null && !database.isBlank()) {
            builder.withDatabaseName(database);
        }
        client = new MilvusServiceClient(builder.build());
        log.info("Milvus 客户端已创建，host={}, port={}, database={}", host, port, database);
        return client;
    }

    /** 知识库使用固定维度与余弦相似度；collection 不存在时由 LangChain4j 自动创建。 */
    @Bean("knowledgeEmbeddingStore")
    @Lazy
    public EmbeddingStore<TextSegment> knowledgeEmbeddingStore(MilvusServiceClient milvusServiceClient) {
        return buildStore(milvusServiceClient, knowledgeCollection, true);
    }

    /** 长期对话记忆独立 collection，避免与平台知识混在一起。 */
    @Bean("conversationEmbeddingStore")
    @Lazy
    public EmbeddingStore<TextSegment> conversationEmbeddingStore(MilvusServiceClient milvusServiceClient) {
        return buildStore(milvusServiceClient, conversationCollection, autoFlushOnInsert);
    }

    private EmbeddingStore<TextSegment> buildStore(MilvusServiceClient milvusServiceClient,
                                                    String collectionName,
                                                    boolean autoFlush) {
        log.info("初始化 Milvus 向量库，collection={}, dimension={}", collectionName, dimension);
        return MilvusEmbeddingStore.builder()
                .milvusClient(milvusServiceClient)
                .collectionName(collectionName)
                .dimension(dimension)
                .indexType(IndexType.FLAT)
                .metricType(MetricType.COSINE)
                .consistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .autoFlushOnInsert(autoFlush)
                .retrieveEmbeddingsOnSearch(false)
                .build();
    }

    @PreDestroy
    public void close() {
        if (client == null) {
            return;
        }
        try {
            client.close(5000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("关闭 Milvus 客户端时线程被中断", exception);
        }
    }
}
