package com.hmdp.ai.knowledge;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 周期追赶当前发布；独立构建批次完成后才对检索可见，失败不启用部分索引。 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "milvus", name = "auto-import", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexSynchronizer {
    private final AiCustomerKnowledgeService knowledge;
    private final KnowledgeIndexState state;

    KnowledgeIndexSynchronizer(AiCustomerKnowledgeService knowledge, KnowledgeIndexState state) {
        this.knowledge = knowledge;
        this.state = state;
    }

    /** 本机不重叠执行；共享租约另保证多个实例不会同时构建同一集合。 */
    @PostConstruct
    @Scheduled(fixedDelayString = "${hmdp.ai.rag.knowledge.sync-interval-ms:15000}",
            initialDelayString = "${hmdp.ai.rag.knowledge.sync-interval-ms:15000}")
    public synchronized void synchronize() {
        if (!knowledge.isVectorEnabled()) return;
        String publication;
        String build = UUID.randomUUID().toString();
        try {
            publication = knowledge.knowledgeVersion();
            if (state.readyBuild(publication) != null || !state.acquire(publication, build)) return;
        } catch (RuntimeException failure) {
            // 文件错误或协调存储不可用不能触发无协调的重复Embedding。
            log.warn("AI_KNOWLEDGE_INDEX phase=DEFERRED, causeType={}", failure.getClass().getSimpleName());
            return;
        }
        boolean publishUnconfirmed = false;
        boolean published = false;
        try {
            log.info("AI_KNOWLEDGE_INDEX phase=BUILDING, publication={}, build={}", publication, build);
            if (!knowledge.importKnowledge(publication, build)) {
                state.failed(publication, build);
                return;
            }
            if (!publication.equals(knowledge.knowledgeVersion())) {
                log.info("AI_KNOWLEDGE_INDEX phase=SUPERSEDED, publication={}", publication);
                return;
            }
            publishUnconfirmed = true;
            published = state.ready(publication, build);
            publishUnconfirmed = false;
            log.info("AI_KNOWLEDGE_INDEX phase={}, publication={}, build={}", published ? "READY" : "LEASE_LOST", publication, build);
        } catch (RuntimeException failure) {
            log.warn("AI_KNOWLEDGE_INDEX phase={}, publication={}, causeType={}",
                    publishUnconfirmed ? "PUBLISH_UNCONFIRMED" : "FAILED", publication, failure.getClass().getSimpleName());
            try { state.failed(publication, build); }
            catch (RuntimeException unavailable) { log.warn("AI_KNOWLEDGE_INDEX phase=RETRY_STATE_UNAVAILABLE"); }
        } finally {
            // 发布确认超时可能已经写入指针，不能清理这个状态不明的批次。
            if (!published && !publishUnconfirmed) knowledge.discardBuild(publication, build);
            try { state.release(build); }
            catch (RuntimeException unavailable) { log.warn("AI_KNOWLEDGE_INDEX phase=LEASE_RELEASE_UNAVAILABLE"); }
        }
    }
}
