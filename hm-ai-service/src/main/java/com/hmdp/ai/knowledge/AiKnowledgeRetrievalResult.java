package com.hmdp.ai.knowledge;

import java.util.List;

/**
 * 知识检索结果，调用方可将 sources 用于引用展示或链路观测。
 */
public record AiKnowledgeRetrievalResult(
        String context,
        List<Source> sources,
        RetrievalMode retrievalMode,
        boolean unavailable,
        Long shopId
) {

    public AiKnowledgeRetrievalResult(String context, List<Source> sources, RetrievalMode mode) {
        this(context, sources, mode, false, null);
    }

    public AiKnowledgeRetrievalResult(String context, List<Source> sources, RetrievalMode mode, boolean unavailable) {
        this(context, sources, mode, unavailable, null);
    }

    public AiKnowledgeRetrievalResult {
        context = context == null ? "" : context;
        sources = sources == null ? List.of() : List.copyOf(sources);
        retrievalMode = retrievalMode == null ? RetrievalMode.EMPTY : retrievalMode;
    }

    public boolean hasSources() {
        return !sources.isEmpty();
    }

    public enum RetrievalMode {
        VECTOR,
        KEYWORD,
        EMPTY
    }

    /**
     * 单条知识来源；relevance 在不同检索方式下仅用于同方式内排序和观测。
     */
    public record Source(String documentId, String title, String source, String knowledgeVersion, double relevance,
                         String content, String documentVersion) {
        public Source(String documentId, String title, String source, String knowledgeVersion, double relevance, String content) {
            this(documentId, title, source, knowledgeVersion, relevance, content, null);
        }
        public Source(String documentId, String title, String source, String knowledgeVersion, double relevance) {
            this(documentId, title, source, knowledgeVersion, relevance, "");
        }
    }
}
