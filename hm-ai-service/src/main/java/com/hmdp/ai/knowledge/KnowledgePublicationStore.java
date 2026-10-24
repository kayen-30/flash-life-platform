package com.hmdp.ai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 发布指针与不可变历史在同一数据库事务中提交，所有读取副本使用这一权威状态。 */
@Service
@Slf4j
class KnowledgePublicationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgePublicationCodec codec;

    @Value("${hmdp.ai.rag.knowledge.namespace:customer-service}")
    private String namespace;

    KnowledgePublicationStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.codec = new KnowledgePublicationCodec(json);
        this.transaction = new TransactionTemplate(manager);
        // 发布锁只覆盖本地校验后的数据库写入，不持有模型或Embedding调用。
        this.transaction.setTimeout(15);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 每次读取都从当前指针连接到完整修订；缺失、损坏或数据库不可用时不能回退旧文件。 */
    KnowledgeCatalog current() {
        try {
            List<Snapshot> rows = jdbc.query("""
                    SELECT h.generation, h.revision, r.publication_version, r.content
                    FROM ai_knowledge_head h LEFT JOIN ai_knowledge_revision r
                      ON r.namespace=h.namespace AND r.generation=h.generation
                    WHERE h.namespace=?
                    """, (rs, row) -> new Snapshot(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBytes(4)), space());
            if (rows.size() != 1 || rows.get(0).generation() <= 0 || rows.get(0).content() == null) {
                throw new IllegalStateException("共享知识尚无有效发布");
            }
            Snapshot snapshot = rows.get(0);
            KnowledgeCatalog catalog = codec.decode(snapshot.content());
            if (!catalog.revision().equals(snapshot.revision()) || !catalog.version().equals(snapshot.version())) {
                throw new IllegalStateException("共享知识修订身份不一致");
            }
            return catalog;
        } catch (IOException | RuntimeException unavailable) {
            log.warn("共享知识发布不可用，causeType={}", unavailable.getClass().getSimpleName());
            throw new IllegalStateException("共享知识发布不可用");
        }
    }

    /** 只返回发布元数据；不经此管理接口导出私有正文。 */
    Metadata metadata() {
        List<Metadata> rows = jdbc.query("""
                SELECT h.generation, h.revision, r.publication_version, r.document_count
                FROM ai_knowledge_head h LEFT JOIN ai_knowledge_revision r
                  ON r.namespace=h.namespace AND r.generation=h.generation
                WHERE h.namespace=?
                """, (rs, row) -> {
            long generation = rs.getLong(1);
            String revision = rs.getString(2), version = rs.getString(3);
            if (generation < 0 || generation > 0 && (revision == null || version == null)) {
                throw new IllegalStateException("共享知识发布状态损坏");
            }
            return new Metadata(generation, revision, version, rs.getInt(4));
        }, space());
        return rows.isEmpty() ? new Metadata(0, null, null, 0) : rows.get(0);
    }

    /** 先完整验证，再按客户端读到的ETag条件发布；并发覆盖、版本复用和部分写入均不能生效。 */
    Metadata publish(byte[] bytes, String expectedEtag) {
        KnowledgeCatalog next;
        Map<String, String> digests;
        try {
            next = codec.decode(bytes);
            digests = codec.documentDigests(bytes);
        } catch (IOException | RuntimeException invalid) {
            throw new InvalidPublication();
        }
        return transaction.execute(status -> {
            String space = space();
            // 即使已有发布也先取排他锁，避免两个发布者先拿共享锁后同时升级而死锁。
            jdbc.update("INSERT INTO ai_knowledge_head(namespace,generation) VALUES (?,0) ON DUPLICATE KEY UPDATE generation=generation", space);
            Metadata before = jdbc.queryForObject("SELECT generation,revision FROM ai_knowledge_head WHERE namespace=? FOR UPDATE",
                    (rs, row) -> new Metadata(rs.getLong(1), rs.getString(2), null, 0), space);
            if (before == null || !before.etag().equals(expectedEtag)) throw new PublicationConflict();
            if (next.revision().equals(before.revision())) return metadata();
            List<String> prior = jdbc.query("SELECT revision FROM ai_knowledge_revision WHERE namespace=? AND publication_version=? LIMIT 1",
                    (rs, row) -> rs.getString(1), space, next.version());
            if (!prior.isEmpty() && !prior.get(0).equals(next.revision())) throw new PublicationConflict();
            for (KnowledgeDocument document : next.documents()) {
                List<String> bound = jdbc.query("""
                        SELECT document_digest FROM ai_knowledge_document_version
                        WHERE namespace=? AND document_id=? AND document_version=?
                        """, (rs, row) -> rs.getString(1), space, document.id(), document.version());
                String digest = digests.get(document.id());
                if (!bound.isEmpty() && !bound.get(0).equals(digest)) throw new PublicationConflict();
                if (bound.isEmpty()) jdbc.update("""
                        INSERT INTO ai_knowledge_document_version(namespace,document_id,document_version,document_digest)
                        VALUES (?,?,?,?)
                        """, space, document.id(), document.version(), digest);
            }
            long generation = Math.addExact(before.generation(), 1);
            jdbc.update("""
                    INSERT INTO ai_knowledge_revision(namespace,generation,publication_version,revision,document_count,content)
                    VALUES (?,?,?,?,?,?)
                    """, space, generation, next.version(), next.revision(), next.documents().size(), bytes);
            jdbc.update("UPDATE ai_knowledge_head SET generation=?,revision=? WHERE namespace=?",
                    generation, next.revision(), space);
            return new Metadata(generation, next.revision(), next.version(), next.documents().size());
        });
    }

    private String space() {
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalStateException("知识发布命名空间无效");
        }
        return namespace;
    }

    record Metadata(long generation, String revision, String publicationVersion, int documentCount) {
        String etag() { return "\"" + generation + (revision == null ? "" : "-" + revision) + "\""; }
    }
    private record Snapshot(long generation, String revision, String version, byte[] content) { }
    static final class InvalidPublication extends RuntimeException { }
    static final class PublicationConflict extends RuntimeException { }
}
