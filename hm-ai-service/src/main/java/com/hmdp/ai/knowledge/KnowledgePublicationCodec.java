package com.hmdp.ai.knowledge;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** 读取和发布复用同一严格清单协议，版本、权限与规则冲突不能走两套校验。 */
final class KnowledgePublicationCodec {
    static final int MAX_BYTES = 1024 * 1024;
    private final ObjectMapper objectMapper;

    KnowledgePublicationCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        // 全局标量开关不约束String，规则取值和版本不能把数字/布尔值静默转成文本。
        this.objectMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    /** 全量校验成功后才可创建修订，不能边解析边发布部分知识。 */
    KnowledgeCatalog decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("知识发布快照大小无效");
        return readCatalog(bytes, digest(bytes));
    }

    /** 对象属性按固定顺序编码；文档的版本绑定正文、规则、来源与权限，格式空白不产生新内容。 */
    Map<String, String> documentDigests(byte[] bytes) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode item : objectMapper.readTree(bytes).path("documents")) {
            if ("PUBLISHED".equals(item.path("status").asText())) {
                result.put(item.path("id").asText(), digest(objectMapper.writeValueAsBytes(canonical(item))));
            }
        }
        return result;
    }

    private Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.fields().forEachRemaining(field -> result.put(field.getKey(), canonical(field.getValue())));
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private KnowledgeCatalog readCatalog(byte[] bytes, String revision) throws IOException {
        KnowledgeResourceFile resourceFile = objectMapper.readValue(bytes, KnowledgeResourceFile.class);
        if (resourceFile == null || resourceFile.documents == null) throw new IllegalArgumentException("缺少发布清单");
        String version = requireIdentifier(resourceFile.version);
        Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();
        Map<RuleKey, List<DeclaredRule>> rules = new LinkedHashMap<>();
        for (KnowledgeResourceItem item : resourceFile.documents) {
            if (item == null || !Set.of("PUBLISHED", "DRAFT", "RETIRED").contains(item.status == null ? "" : item.status)) {
                throw new IllegalArgumentException("文档缺少合法发布状态");
            }
            if (!"PUBLISHED".equals(item.status)) continue;
            KnowledgeDocument document = toDocument(item);
            // 同一逻辑文档只能有一个明确发布版本；不按排列顺序、时间或向量分数猜哪个版本有效。
            if (documents.putIfAbsent(document.id(), document) != null) {
                throw new IllegalArgumentException("发布清单包含冲突的文档版本");
            }
            validateRules(item.rules, document, rules);
        }
        return new KnowledgeCatalog(version, new ArrayList<>(documents.values()), revision);
    }

    /** 发布者声明规范化规则及适用范围；在召回前拒绝冲突，不能交给排名或模型选边。 */
    private void validateRules(List<KnowledgeRule> declarations, KnowledgeDocument document, Map<RuleKey, List<DeclaredRule>> rules) {
        if (declarations == null) return;
        for (KnowledgeRule rule : declarations) {
            if (rule == null || isBlank(rule.quote()) || !document.content().contains(rule.quote())) {
                throw new IllegalArgumentException("规则声明缺少正文依据");
            }
            String key = requireIdentifier(rule.key()), scope = requireIdentifier(rule.scope());
            if (!key.matches("[a-z][a-z0-9._-]{0,79}") || !scope.matches("[a-z][a-z0-9._-]{0,79}")) {
                throw new IllegalArgumentException("规则键与范围必须使用规范小写标识");
            }
            String value = requireIdentifier(rule.value());
            List<DeclaredRule> prior = rules.computeIfAbsent(new RuleKey(key, scope), ignored -> new ArrayList<>());
            // ponytail: 清单受1MiB限制，先逐项比较；大规模发布再用区间索引减少比较次数。
            if (prior.stream().anyMatch(existing -> !existing.value().equals(value) && document.overlaps(existing.document()))) {
                // 不记录规则值、引用或文档名，私有发布冲突也不能经故障日志泄露。
                throw new IllegalArgumentException("发布清单包含冲突的规则声明");
            }
            prior.add(new DeclaredRule(value, document));
        }
    }

    private record RuleKey(String key, String scope) { }
    private record DeclaredRule(String value, KnowledgeDocument document) { }

    private KnowledgeDocument toDocument(KnowledgeResourceItem item) {
        String id = requireIdentifier(item.id);
        String version = requireIdentifier(item.version);
        if (isBlank(item.content) || item.access == null) throw new IllegalArgumentException("发布文档缺少内容或访问策略");
        List<String> keywords = item.keywords == null ? List.of() : item.keywords.stream()
                .filter(keyword -> !isBlank(keyword))
                .map(String::trim)
                .distinct()
                .toList();
        String title = isBlank(item.title) ? item.id.trim() : item.title.trim();
        String source = isBlank(item.source) ? "平台 FAQ - " + title : item.source.trim();
        return new KnowledgeDocument(id, title, keywords, item.content.trim(), source, version, item.access,
                instant(item.effectiveFrom), instant(item.effectiveUntil), item.shopIds);
    }

    /** 生效时间必须带UTC或偏移量；不根据服务器本地时区猜测无时区日期。 */
    private Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private String requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IllegalArgumentException("知识标识或版本无效");
        return value;
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256不可用"); }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static class KnowledgeResourceFile {
        public String version;
        public List<KnowledgeResourceItem> documents;
    }

    static class KnowledgeResourceItem {
        public String id;
        public String title;
        public List<String> keywords;
        public String content;
        public String source;
        public String version;
        public String status;
        public KnowledgeAccess access;
        public List<KnowledgeRule> rules;
        public String effectiveFrom;
        public String effectiveUntil;
        public List<Long> shopIds;
    }

    record KnowledgeRule(String key, String scope, String value, String quote) { }
}
