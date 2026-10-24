-- 发布快照、当前指针和文档版本绑定由AI服务拥有；重复执行仅补建新表。
CREATE TABLE IF NOT EXISTS ai_knowledge_head (
    namespace VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_knowledge_revision (
    namespace VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    generation BIGINT NOT NULL,
    publication_version VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_count INT NOT NULL,
    content MEDIUMBLOB NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (namespace, generation),
    KEY idx_ai_publication_version (namespace, publication_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_knowledge_document_version (
    namespace VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    document_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    document_version VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    document_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (namespace, document_id, document_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
