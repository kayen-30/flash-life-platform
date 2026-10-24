package com.hmdp.ai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.auth.UserRoles;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 共享发布是在线权威；classpath/file保留为显式离线导入与受控验证模式。 */
@Slf4j
@Component
class ClasspathKnowledgeResourceLoader {
    private final ResourceLoader resourceLoader;
    private final KnowledgePublicationCodec codec;
    private volatile KnowledgeCatalog catalog;
    private KnowledgePublicationStore publications;

    @Value("${hmdp.ai.rag.knowledge.resource:shared}")
    private String resourceLocation;

    ClasspathKnowledgeResourceLoader(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.codec = new KnowledgePublicationCodec(objectMapper);
    }

    @Autowired
    void publicationStore(KnowledgePublicationStore publications) { this.publications = publications; }

    /** 每次共享读取都查询当前指针；权威源不可确认时不能沿用本地文件或旧权限缓存。 */
    KnowledgeCatalog load() {
        if ("shared".equals(resourceLocation)) {
            if (publications == null) throw new IllegalStateException("共享知识发布未配置");
            return publications.current();
        }
        boolean classpath = resourceLocation != null && resourceLocation.startsWith("classpath:");
        if (!classpath && (resourceLocation == null || !resourceLocation.startsWith("file:"))) {
            throw new IllegalStateException("知识发布资源只允许shared或服务端配置的classpath/file路径");
        }
        if (classpath && catalog != null) return catalog;
        Resource resource = resourceLoader.getResource(resourceLocation);
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(KnowledgePublicationCodec.MAX_BYTES + 1);
            String revision = KnowledgePublicationCodec.digest(bytes);
            synchronized (this) {
                if (catalog == null || !revision.equals(catalog.revision())) catalog = codec.decode(bytes);
                return catalog;
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("知识发布快照不可用，causeType={}", failure.getClass().getSimpleName());
            throw new IllegalStateException("知识发布快照不可用");
        }
    }
}

record KnowledgeCatalog(String version, List<KnowledgeDocument> documents, String revision) {

    KnowledgeCatalog {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    String indexVersion() { return version + "@" + revision; }

    KnowledgeDocument documentById(String documentId) {
        for (KnowledgeDocument document : documents) {
            if (document.id().equals(documentId)) {
                return document;
            }
        }
        return null;
    }
}

record KnowledgeDocument(String id, String title, List<String> keywords, String content, String source,
                         String version, KnowledgeAccess access, Instant effectiveFrom, Instant effectiveUntil,
                         List<Long> shopIds) {

    KnowledgeDocument {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        shopIds = shopIds == null ? List.of() : List.copyOf(shopIds);
        if (shopIds.stream().anyMatch(shopId -> shopId <= 0)
                || (effectiveFrom != null && effectiveUntil != null && !effectiveFrom.isBefore(effectiveUntil))) {
            throw new IllegalArgumentException("知识适用时间或门店范围无效");
        }
    }

    String searchText() {
        return title + "\n" + content;
    }

    /** 含开始、不含结束；只消费服务器时钟与已确认实体，不接受模型决定适用范围。 */
    boolean appliesAt(Instant instant, Long shopId) {
        return (effectiveFrom == null || !instant.isBefore(effectiveFrom))
                && (effectiveUntil == null || instant.isBefore(effectiveUntil))
                && (shopIds.isEmpty() || (shopId != null && shopIds.contains(shopId)));
    }

    /** 只有同一时段、可能作用于同一门店的声明才会发生业务冲突。 */
    boolean overlaps(KnowledgeDocument other) {
        return (effectiveUntil == null || other.effectiveFrom() == null || other.effectiveFrom().isBefore(effectiveUntil))
                && (other.effectiveUntil() == null || effectiveFrom == null || effectiveFrom.isBefore(other.effectiveUntil()))
                && (shopIds.isEmpty() || other.shopIds().isEmpty() || shopIds.stream().anyMatch(other.shopIds()::contains));
    }
}

/** 权限来自可信登录上下文和发布清单，不接受用户提示或模型提供的角色；管理员也没有隐式越权。 */
record KnowledgeAccess(Visibility visibility, List<String> roles, List<Long> userIds) {
    enum Visibility { PUBLIC, RESTRICTED }
    KnowledgeAccess {
        roles = roles == null ? List.of() : List.copyOf(roles);
        userIds = userIds == null ? List.of() : List.copyOf(userIds);
        if (visibility == null || roles.stream().anyMatch(role -> !Set.of(UserRoles.USER, UserRoles.ADMIN).contains(role))
                || userIds.stream().anyMatch(id -> id <= 0)
                || (visibility == Visibility.PUBLIC && (!roles.isEmpty() || !userIds.isEmpty()))) {
            throw new IllegalArgumentException("文档访问策略无效");
        }
    }
    boolean canRead(UserDTO user) {
        if (visibility == Visibility.PUBLIC) return true;
        return user != null && user.getId() != null && user.getId() > 0
                && (userIds.contains(user.getId()) || (user.getRole() != null && roles.contains(user.getRole())));
    }
}
