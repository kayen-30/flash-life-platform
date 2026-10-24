package com.hmdp.ai.knowledge;

import com.hmdp.utils.GatewayHeaders;
import com.hmdp.utils.InternalTokenValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.util.Map;

/** 发布管理只接受内部凭证和专属发布凭证，普通用户及其他服务不能改写知识。 */
@RestController
@RequestMapping("/internal/knowledge/publication")
@Slf4j
class KnowledgePublicationController {
    static final String PUBLISH_TOKEN_HEADER = "X-Knowledge-Publish-Token";
    private final KnowledgePublicationStore publications;

    @Value("${hmdp.internal-token}")
    private String internalToken;
    @Value("${hmdp.ai.rag.knowledge.publish-token:}")
    private String publishToken;

    KnowledgePublicationController(KnowledgePublicationStore publications) {
        this.publications = publications;
    }

    /** 元数据只用于发布条件，不导出正文；未初始化时返回ETag \"0\"。 */
    @GetMapping
    ResponseEntity<?> metadata(HttpServletRequest request) {
        if (!authorized(request)) return error(403, "PUBLICATION_FORBIDDEN");
        try {
            return success(publications.metadata());
        } catch (RuntimeException unavailable) {
            return unavailable(unavailable);
        }
    }

    /** 鉴权后限量读取正文，校验和数据库事务成功后才返回新的发布ETag。 */
    @PutMapping(consumes = "application/json")
    ResponseEntity<?> publish(HttpServletRequest request) {
        if (!authorized(request)) return error(403, "PUBLICATION_FORBIDDEN");
        String expected = request.getHeader(HttpHeaders.IF_MATCH);
        if (expected == null) return error(428, "PUBLICATION_PRECONDITION_REQUIRED");
        if (!expected.matches("\"(?:0|[1-9][0-9]{0,18}-[a-f0-9]{64})\"")) {
            return error(400, "PUBLICATION_PRECONDITION_INVALID");
        }
        if (request.getContentLengthLong() > KnowledgePublicationCodec.MAX_BYTES) {
            return error(413, "PUBLICATION_TOO_LARGE");
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(KnowledgePublicationCodec.MAX_BYTES + 1);
            if (bytes.length > KnowledgePublicationCodec.MAX_BYTES) return error(413, "PUBLICATION_TOO_LARGE");
            return success(publications.publish(bytes, expected));
        } catch (KnowledgePublicationStore.InvalidPublication invalid) {
            return error(422, "PUBLICATION_INVALID");
        } catch (KnowledgePublicationStore.PublicationConflict conflict) {
            // 冲突后必须重新读取当前状态，服务端不自动覆盖也不泄露冲突文档信息。
            return error(409, "PUBLICATION_CONFLICT");
        } catch (IOException malformed) {
            return error(400, "PUBLICATION_READ_FAILED");
        } catch (RuntimeException unavailable) {
            return unavailable(unavailable);
        }
    }

    private boolean authorized(HttpServletRequest request) {
        return InternalTokenValidator.matches(internalToken, request.getHeader(GatewayHeaders.INTERNAL_TOKEN))
                && InternalTokenValidator.matches(publishToken, request.getHeader(PUBLISH_TOKEN_HEADER));
    }

    private ResponseEntity<?> success(KnowledgePublicationStore.Metadata metadata) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").eTag(metadata.etag()).body(metadata);
    }

    private ResponseEntity<?> unavailable(RuntimeException unavailable) {
        // 不输出数据库异常内容，避免SQL参数、地址或私有文档进入日志。
        log.warn("知识发布管理不可用，causeType={}", unavailable.getClass().getSimpleName());
        return error(503, "PUBLICATION_UNAVAILABLE");
    }

    private ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("code", code));
    }
}
