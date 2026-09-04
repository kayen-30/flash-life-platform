package com.hmdp.service;

import com.hmdp.service.ai.AiCustomerKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * 首次启动时把类路径 FAQ 导入 Milvus；标记使用普通 Redis，避免依赖 Redis Stack。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "milvus", name = "auto-import", havingValue = "true", matchIfMissing = true)
public class DataMigrationService {

    private static final String IMPORT_MARKER_PREFIX = "ai:milvus:knowledge:imported:";

    private final AiCustomerKnowledgeService knowledgeService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${milvus.knowledge-collection:shop_knowledge}")
    private String knowledgeCollection;

    public DataMigrationService(AiCustomerKnowledgeService knowledgeService,
                                StringRedisTemplate stringRedisTemplate) {
        this.knowledgeService = knowledgeService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 先写入当前版本，成功后再清理旧向量，避免导入失败导致知识库整体不可用。 */
    @PostConstruct
    public void importInitialKnowledge() {
        if (!knowledgeService.isVectorEnabled()) {
            log.info("向量 RAG 未开启，跳过 Milvus 初始知识导入");
            return;
        }
        String version = knowledgeService.knowledgeVersion();
        String marker = IMPORT_MARKER_PREFIX + knowledgeCollection + ':' + version;
        if (isImported(marker)) {
            log.info("Milvus 知识库已导入，marker={}", marker);
            if (!knowledgeService.deleteVersionsExcept(version)) {
                log.warn("Milvus 旧版本知识未能清理，当前版本仍可用，version={}", version);
            }
            return;
        }

        log.info("开始导入初始知识库到 Milvus，collection={}", knowledgeCollection);
        try {
            if (!knowledgeService.importClasspathKnowledge()) {
                log.warn("Milvus 初始知识导入未完成，本次不写入导入标记");
                return;
            }
        } catch (RuntimeException exception) {
            // Milvus 尚未就绪时允许应用先启动，后续可重新开启导入或重启服务。
            log.warn("Milvus 初始知识导入失败，本次不写入导入标记", exception);
            return;
        }
        if (!knowledgeService.deleteVersionsExcept(version)) {
            // 检索已经按当前版本过滤，清理失败不会让新版本回退到旧知识。
            log.warn("Milvus 旧版本知识未能清理，当前版本仍可用，version={}", version);
        }
        try {
            stringRedisTemplate.opsForValue().set(marker, "1");
            log.info("Milvus 初始知识导入标记已写入，marker={}", marker);
        } catch (RuntimeException exception) {
            // 导入已经成功，标记失败只会造成下次启动重复写入，不阻断本次服务。
            log.warn("写入 Milvus 导入标记失败，后续启动可能重复导入");
        }
    }

    private boolean isImported(String marker) {
        try {
            return "1".equals(stringRedisTemplate.opsForValue().get(marker));
        } catch (RuntimeException exception) {
            log.warn("读取 Milvus 导入标记失败，将继续尝试导入");
            return false;
        }
    }
}
