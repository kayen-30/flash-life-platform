package com.hmdp.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
class ClasspathKnowledgeResourceLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private volatile KnowledgeCatalog catalog;

    @Value("${hmdp.ai.rag.knowledge.resource:classpath:knowledge/customer-service-v1.json}")
    private String resourceLocation;

    ClasspathKnowledgeResourceLoader(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * 类路径资源在运行期间不变，首次成功读取后缓存以避免每次请求解析文件。
     */
    KnowledgeCatalog load() {
        KnowledgeCatalog cached = catalog;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (catalog == null) {
                catalog = readCatalog();
            }
            return catalog;
        }
    }

    private KnowledgeCatalog readCatalog() {
        if (resourceLocation == null || !resourceLocation.startsWith("classpath:")) {
            log.warn("RAG 知识资源必须使用 classpath 路径，当前配置已忽略: {}", resourceLocation);
            return KnowledgeCatalog.empty();
        }
        Resource resource = resourceLoader.getResource(resourceLocation);
        try (InputStream inputStream = resource.getInputStream()) {
            KnowledgeResourceFile resourceFile = objectMapper.readValue(inputStream, KnowledgeResourceFile.class);
            String version = normalizeVersion(resourceFile.version);
            Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();
            if (resourceFile.documents != null) {
                for (KnowledgeResourceItem item : resourceFile.documents) {
                    KnowledgeDocument document = toDocument(item);
                    if (document == null) {
                        continue;
                    }
                    if (documents.putIfAbsent(document.id(), document) != null) {
                        log.warn("RAG 知识资源存在重复文档 id，已忽略重复项: {}", document.id());
                    }
                }
            }
            if (documents.isEmpty()) {
                log.warn("RAG 知识资源未包含有效文档: {}", resourceLocation);
            }
            return new KnowledgeCatalog(version, new ArrayList<>(documents.values()));
        } catch (IOException | RuntimeException e) {
            // 资源缺失或格式错误时保留关键词兜底链路，不阻塞 AI 服务启动。
            log.warn("RAG 知识资源加载失败，将返回空知识库: {}", resourceLocation, e);
            return KnowledgeCatalog.empty();
        }
    }

    private KnowledgeDocument toDocument(KnowledgeResourceItem item) {
        if (item == null || isBlank(item.id) || isBlank(item.content)) {
            log.warn("RAG 知识文档缺少 id 或 content，已忽略");
            return null;
        }
        List<String> keywords = item.keywords == null ? List.of() : item.keywords.stream()
                .filter(keyword -> !isBlank(keyword))
                .map(String::trim)
                .distinct()
                .toList();
        String title = isBlank(item.title) ? item.id.trim() : item.title.trim();
        String source = isBlank(item.source) ? resourceLocation : item.source.trim();
        return new KnowledgeDocument(item.id.trim(), title, keywords, item.content.trim(), source);
    }

    private String normalizeVersion(String version) {
        if (isBlank(version)) {
            log.warn("RAG 知识资源缺少版本号，将使用 unknown 命名空间: {}", resourceLocation);
            return "unknown";
        }
        return version.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class KnowledgeResourceFile {
        public String version;
        public List<KnowledgeResourceItem> documents;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class KnowledgeResourceItem {
        public String id;
        public String title;
        public List<String> keywords;
        public String content;
        public String source;
    }
}

record KnowledgeCatalog(String version, List<KnowledgeDocument> documents) {

    KnowledgeCatalog {
        version = version == null || version.isBlank() ? "unknown" : version;
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    static KnowledgeCatalog empty() {
        return new KnowledgeCatalog("unknown", List.of());
    }

    KnowledgeDocument documentById(String documentId) {
        for (KnowledgeDocument document : documents) {
            if (document.id().equals(documentId)) {
                return document;
            }
        }
        return null;
    }
}

record KnowledgeDocument(String id, String title, List<String> keywords, String content, String source) {

    KnowledgeDocument {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    String searchText() {
        return title + "\n" + content;
    }
}
