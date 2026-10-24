package com.hmdp.ai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.GatewayHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 回归跨实例知识分叉、并发覆盖和管理接口越权；数据库部分必须显式配置真实MySQL。 */
class KnowledgePublicationStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishingRequiresBothCredentialsAndDoesNotReadUnauthorizedBody() throws Exception {
        var store = mock(KnowledgePublicationStore.class);
        var controller = controller(store);
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("未授权请求不能读取正文");
            }
        };
        request.addHeader(GatewayHeaders.INTERNAL_TOKEN, "internal-test");
        assertThat(controller.publish(request).getStatusCode().value()).isEqualTo(403);
        request.addHeader(KnowledgePublicationController.PUBLISH_TOKEN_HEADER, "publish-test");
        request.removeHeader(GatewayHeaders.INTERNAL_TOKEN);
        assertThat(controller.publish(request).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(store);
    }

    @Test
    void blankPublisherCredentialDisablesManagement() throws Exception {
        var store = mock(KnowledgePublicationStore.class);
        var controller = controller(store);
        ReflectionTestUtils.setField(controller, "publishToken", "");
        mvc(controller).perform(authorized(get("/internal/knowledge/publication")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PUBLICATION_FORBIDDEN"));
        verifyNoInteractions(store);
    }

    @Test
    void publicationRequiresExactPreconditionAndBoundedBody() throws Exception {
        var store = mock(KnowledgePublicationStore.class);
        var mvc = mvc(controller(store));
        mvc.perform(authorized(put("/internal/knowledge/publication")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(428));
        mvc.perform(authorized(put("/internal/knowledge/publication")).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.IF_MATCH, "*").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authorized(put("/internal/knowledge/publication")).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.IF_MATCH, "\"0\"").content(new byte[KnowledgePublicationCodec.MAX_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(store);
    }

    @Test
    void metadataAndPublicationExposeEtagWithoutPrivateContent() throws Exception {
        var store = mock(KnowledgePublicationStore.class);
        var metadata = new KnowledgePublicationStore.Metadata(1, "a".repeat(64), "v1", 1);
        when(store.metadata()).thenReturn(metadata);
        when(store.publish(any(), eq("\"0\""))).thenReturn(metadata);
        var mvc = mvc(controller(store));
        mvc.perform(authorized(get("/internal/knowledge/publication")))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, metadata.etag()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.documentCount").value(1)).andExpect(jsonPath("$.content").doesNotExist());
        mvc.perform(authorized(put("/internal/knowledge/publication")).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.IF_MATCH, "\"0\"").content("{}"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, metadata.etag()));
    }

    @Test
    void invalidConflictingAndUnavailablePublicationsHaveRedactedErrors() throws Exception {
        var store = mock(KnowledgePublicationStore.class);
        when(store.publish(any(), anyString())).thenThrow(new KnowledgePublicationStore.InvalidPublication(),
                new KnowledgePublicationStore.PublicationConflict(), new DataAccessResourceFailureException("private-content"));
        var mvc = mvc(controller(store));
        for (int expected : List.of(422, 409, 503)) {
            mvc.perform(authorized(put("/internal/knowledge/publication")).contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.IF_MATCH, "\"0\"").content("{}"))
                    .andExpect(status().is(expected)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-content"))));
        }
    }

    @Test
    void sharedAuthorityFailureNeverFallsBackToCachedLocalPublication() {
        var loader = new ClasspathKnowledgeResourceLoader(new DefaultResourceLoader(), json);
        ReflectionTestUtils.setField(loader, "resourceLocation", "classpath:knowledge/customer-service-v1.json");
        assertThat(loader.load().documents()).isNotEmpty();
        var store = mock(KnowledgePublicationStore.class);
        when(store.current()).thenThrow(new IllegalStateException("共享发布不可用"));
        loader.publicationStore(store);
        ReflectionTestUtils.setField(loader, "resourceLocation", "shared");
        assertThatThrownBy(loader::load).isInstanceOf(IllegalStateException.class);
        verify(store).current();
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "LIFEFLASH_KNOWLEDGE_MYSQL_URL", matches = ".+")
    class MysqlTransactions {
        private JdbcTemplate jdbc;
        private String namespace;
        private KnowledgePublicationStore first;
        private KnowledgePublicationStore second;

        @BeforeEach
        void independentPublishersAndReaders() {
            namespace = "eval_" + UUID.randomUUID().toString().replace("-", "");
            first = databaseStore();
            second = databaseStore();
        }

        private KnowledgePublicationStore databaseStore() {
            var source = new DriverManagerDataSource(System.getenv("LIFEFLASH_KNOWLEDGE_MYSQL_URL"),
                    System.getenv("LIFEFLASH_KNOWLEDGE_MYSQL_USER"), System.getenv("LIFEFLASH_KNOWLEDGE_MYSQL_PASSWORD"));
            jdbc = new JdbcTemplate(source);
            var store = new KnowledgePublicationStore(jdbc, new DataSourceTransactionManager(source), json);
            ReflectionTestUtils.setField(store, "namespace", namespace);
            return store;
        }

        @Test
        void uninitializedAuthorityFailsClosedAndTwoReadersObserveUpdates() throws Exception {
            assertThat(first.metadata().etag()).isEqualTo("\"0\"");
            assertThatThrownBy(first::current).isInstanceOf(IllegalStateException.class);
            byte[] v1 = publication("v1", doc("voucher", "1", "规则一", Map.of("visibility", "PUBLIC")));
            var one = first.publish(v1, "\"0\"");
            var firstLoader = sharedLoader(first);
            var secondLoader = sharedLoader(second);
            assertThat(firstLoader.load()).isEqualTo(secondLoader.load());
            byte[] v2 = publication("v2", doc("voucher", "2", "规则二", Map.of("visibility", "RESTRICTED", "userIds", List.of(7))));
            first.publish(v2, one.etag());
            assertThat(firstLoader.load()).isEqualTo(secondLoader.load());
            assertThat(secondLoader.load().documentById("voucher").content()).isEqualTo("规则二");
            assertThat(secondLoader.load().documentById("voucher").access().canRead(null)).isFalse();
        }

        @Test
        void concurrentBootstrapAndUpdateEachHaveExactlyOneWinner() throws Exception {
            race("\"0\"", "a1", "b1");
            assertThat(first.metadata().generation()).isEqualTo(1);
            race(first.metadata().etag(), "a2", "b2");
            assertThat(first.metadata().generation()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_revision WHERE namespace=?", Integer.class, namespace)).isEqualTo(2);
        }

        private void race(String expected, String a, String b) throws Exception {
            var start = new CountDownLatch(1);
            var tasks = List.of(CompletableFuture.supplyAsync(() -> contender(first, expected, a, start)),
                    CompletableFuture.supplyAsync(() -> contender(second, expected, b, start)));
            start.countDown();
            assertThat(List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("published", "conflict");
        }

        private String contender(KnowledgePublicationStore store, String expected, String label, CountDownLatch start) {
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("未启动并发测试");
                store.publish(publication(label, doc("voucher", label, label, Map.of("visibility", "PUBLIC"))), expected);
                return "published";
            } catch (KnowledgePublicationStore.PublicationConflict conflict) {
                return "conflict";
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }

        @Test
        void invalidCandidateRetainsTheLastValidPublication() throws Exception {
            var one = first.publish(publication("v1", doc("voucher", "1", "已审核规则", Map.of("visibility", "PUBLIC"))), "\"0\"");
            assertThatThrownBy(() -> second.publish("{\"version\":true}".getBytes(StandardCharsets.UTF_8), one.etag()))
                    .isInstanceOf(KnowledgePublicationStore.InvalidPublication.class);
            assertThat(first.metadata()).isEqualTo(one);
            assertThat(first.current().documentById("voucher").content()).isEqualTo("已审核规则");
        }

        @Test
        void sameDocumentVersionCannotChangeContentOrPermissions() throws Exception {
            var one = first.publish(publication("v1", doc("voucher", "1", "旧规则", Map.of("visibility", "PUBLIC"))), "\"0\"");
            for (var changed : List.of(doc("voucher", "1", "新规则", Map.of("visibility", "PUBLIC")),
                    doc("voucher", "1", "旧规则", Map.of("visibility", "RESTRICTED", "userIds", List.of(7))))) {
                assertThatThrownBy(() -> second.publish(publication("v2", changed), one.etag()))
                        .isInstanceOf(KnowledgePublicationStore.PublicationConflict.class);
                assertThat(first.metadata()).isEqualTo(one);
            }
        }

        @Test
        void sameDocumentVersionCannotChangeEffectiveTimeOrShopScope() throws Exception {
            var original = doc("voucher", "1", "规则原文", Map.of("visibility", "PUBLIC"));
            var one = first.publish(publication("v1", original), "\"0\"");
            for (var fields : List.of(Map.of("effectiveFrom", "2026-10-23T08:00:00Z"),
                    Map.of("effectiveUntil", "2030-01-01T00:00:00Z"), Map.of("shopIds", List.of(7)))) {
                var changed = new java.util.LinkedHashMap<>(original);
                changed.putAll(fields);
                assertThatThrownBy(() -> second.publish(publication("v2", changed), one.etag()))
                        .isInstanceOf(KnowledgePublicationStore.PublicationConflict.class);
                assertThat(first.metadata()).isEqualTo(one);
            }
        }

        @Test
        void rollbackUsesANewGenerationAndCannotResurrectStaleEtag() throws Exception {
            byte[] a = publication("v1", doc("voucher", "1", "规则一", Map.of("visibility", "PUBLIC")));
            var one = first.publish(a, "\"0\"");
            var two = second.publish(publication("v2", doc("voucher", "2", "规则二", Map.of("visibility", "PUBLIC"))), one.etag());
            var three = first.publish(a, two.etag());
            assertThat(three.generation()).isEqualTo(3);
            assertThat(three.revision()).isEqualTo(one.revision());
            assertThat(three.etag()).isNotEqualTo(one.etag());
            assertThatThrownBy(() -> second.publish(a, one.etag())).isInstanceOf(KnowledgePublicationStore.PublicationConflict.class);
            assertThat(first.publish(a, three.etag())).isEqualTo(three);
        }

        @Test
        void immutableBindingSurvivesInterveningPublications() throws Exception {
            var one = first.publish(publication("v1", doc("voucher", "1", "规则一", Map.of("visibility", "PUBLIC"))), "\"0\"");
            var two = first.publish(publication("v2", doc("voucher", "2", "规则二", Map.of("visibility", "PUBLIC"))), one.etag());
            assertThatThrownBy(() -> second.publish(publication("v3", doc("voucher", "1", "偷换规则", Map.of("visibility", "PUBLIC"))), two.etag()))
                    .isInstanceOf(KnowledgePublicationStore.PublicationConflict.class);
            assertThat(first.metadata()).isEqualTo(two);
        }

        @Test
        void publicationConflictRollsBackEarlierDocumentBindings() throws Exception {
            var one = first.publish(publication("v1", doc("existing", "1", "旧规则", Map.of("visibility", "PUBLIC"))), "\"0\"");
            assertThatThrownBy(() -> second.publish(publication("v2",
                    doc("new", "1", "先写入的绑定", Map.of("visibility", "PUBLIC")),
                    doc("existing", "1", "版本冲突", Map.of("visibility", "PUBLIC"))), one.etag()))
                    .isInstanceOf(KnowledgePublicationStore.PublicationConflict.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_document_version WHERE namespace=? AND document_id='new'", Integer.class, namespace))
                    .isZero();
            assertThat(first.metadata()).isEqualTo(one);
        }

        @Test
        void publicationLabelsCannotBeReusedForDifferentSnapshots() throws Exception {
            var one = first.publish(publication("v1", doc("voucher", "1", "规则一", Map.of("visibility", "PUBLIC"))), "\"0\"");
            assertThatThrownBy(() -> second.publish(publication("v1", doc("voucher", "2", "规则二", Map.of("visibility", "PUBLIC"))), one.etag()))
                    .isInstanceOf(KnowledgePublicationStore.PublicationConflict.class);
            assertThat(first.metadata()).isEqualTo(one);
        }

        @Test
        void corruptedStoredSnapshotIsRejectedByEveryReader() throws Exception {
            first.publish(publication("v1", doc("voucher", "1", "规则一", Map.of("visibility", "PUBLIC"))), "\"0\"");
            jdbc.update("UPDATE ai_knowledge_revision SET content=? WHERE namespace=?", "{}".getBytes(StandardCharsets.UTF_8), namespace);
            assertThatThrownBy(first::current).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(second::current).isInstanceOf(IllegalStateException.class);
        }

        private ClasspathKnowledgeResourceLoader sharedLoader(KnowledgePublicationStore store) {
            var loader = new ClasspathKnowledgeResourceLoader(new DefaultResourceLoader(), json);
            loader.publicationStore(store);
            ReflectionTestUtils.setField(loader, "resourceLocation", "shared");
            return loader;
        }
    }

    private KnowledgePublicationController controller(KnowledgePublicationStore store) {
        var controller = new KnowledgePublicationController(store);
        ReflectionTestUtils.setField(controller, "internalToken", "internal-test");
        ReflectionTestUtils.setField(controller, "publishToken", "publish-test");
        return controller;
    }

    private MockMvc mvc(KnowledgePublicationController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header(GatewayHeaders.INTERNAL_TOKEN, "internal-test")
                .header(KnowledgePublicationController.PUBLISH_TOKEN_HEADER, "publish-test");
    }

    private Map<String, Object> doc(String id, String version, String content, Map<String, Object> access) {
        return Map.of("id", id, "version", version, "content", content, "keywords", List.of("测试规则"),
                "status", "PUBLISHED", "access", access);
    }

    @SafeVarargs
    private final byte[] publication(String version, Map<String, Object>... documents) throws Exception {
        return json.writeValueAsBytes(Map.of("version", version, "documents", List.of(documents)));
    }
}
