package com.hmdp.ai.agent.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.security.AiPrivacySanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentExecutionLogServiceTest {

    @Test
    void interleavedCompletionsRemainAddressableByUserConversationAndRequest() throws Exception {
        Fixture f = fixture();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstFinish = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> {
                f.service.startLogging(7L, "context01", first);
                f.service.executeToolCall("query_shop_by_id", "{}", () -> "first shop result");
                firstStarted.countDown();
                try {
                    assertThat(allowFirstFinish.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                } finally {
                    f.service.finishLogging();
                }
            });
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            f.service.startLogging(7L, "context01", second);
            f.service.executeToolCall("query_shop_vouchers", "{}", () -> "second voucher result");
            f.service.finishLogging();
            assertThat(f.service.getLogs(7L, "context01", first)).isEmpty();
            allowFirstFinish.countDown();
            pending.get(5, TimeUnit.SECONDS);

            assertThat(f.service.getLogs(7L, "context01", first)).singleElement().satisfies(entry -> {
                assertThat(entry.getRequestId()).isEqualTo(first);
                assertThat(entry.getObservation()).isEqualTo("first shop result");
            });
            assertThat(f.service.getLogs(7L, "context01", second)).singleElement().satisfies(entry -> {
                assertThat(entry.getRequestId()).isEqualTo(second);
                assertThat(entry.getObservation()).isEqualTo("second voucher result");
            });
            assertThat(f.service.getLogs(8L, "context01", first)).isEmpty();
            assertThat(f.service.getLogs(7L, "context02", first)).isEmpty();
            assertThat(f.service.getLogs(7L, "context01", UUID.randomUUID().toString())).isEmpty();
            assertThat(f.service.getLogs(7L, "context01", "../context02")).isEmpty();
            assertThat(f.service.getLogs(7L, "context01", null)).isEmpty();
            assertThat(f.storage).hasSize(2);
        } finally {
            allowFirstFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedPersistenceDoesNotLeakStepsIntoTheNextRequest() {
        Fixture f = fixture();
        AtomicBoolean unavailable = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (unavailable.getAndSet(false)) throw new IllegalStateException("redis unavailable");
            f.storage.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(f.values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        f.service.startLogging(7L, "context01", first);
        f.service.executeToolCall("query_shop_by_id", "{}", () -> "lost diagnostic record");
        assertThatCode(f.service::finishLogging).doesNotThrowAnyException();
        f.service.executeToolCall("unbound", "{}", () -> "must not leak");
        f.service.startLogging(7L, "context01", second);
        f.service.executeToolCall("query_shop_vouchers", "{}", () -> "current result");
        f.service.finishLogging();

        assertThat(f.service.getLogs(7L, "context01", first)).isEmpty();
        assertThat(f.service.getLogs(7L, "context01", second)).singleElement().satisfies(entry -> {
            assertThat(entry.getStep()).isEqualTo(1);
            assertThat(entry.getObservation()).isEqualTo("current result");
        });
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> storage = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            storage.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(values.get(anyString())).thenAnswer(invocation -> storage.get(invocation.getArgument(0)));
        return new Fixture(new AgentExecutionLogService(redis, new ObjectMapper().findAndRegisterModules(),
                new AiPrivacySanitizer()), values, storage);
    }

    private record Fixture(AgentExecutionLogService service, ValueOperations<String, String> values,
                           Map<String, String> storage) { }
}
