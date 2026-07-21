package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.SeckillReservationService;
import com.hmdp.service.VoucherOrderPublisher;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoucherOrderServiceImplTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    @SuppressWarnings("unchecked")
    void seckillPublishesOrderAfterRedisReservationSucceeds() {
        CacheClient cacheClient = mock(CacheClient.class);
        ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        VoucherOrderPublisher orderPublisher = mock(VoucherOrderPublisher.class);
        SeckillReservationService reservationService = mock(SeckillReservationService.class);
        VoucherOrderServiceImpl service = service(
                cacheClient, seckillVoucherService, redisIdWorker, redisTemplate, orderPublisher, reservationService
        );
        saveLoginUser();
        when(cacheClient.queryWithLogicalExpire(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(activeVoucher());
        when(redisIdWorker.nextId("order")).thenReturn(100L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        Result result = service.seckillVoucher(12L);

        assertTrue(result.getSuccess());
        verify(seckillVoucherService, never()).getById(12L);
        verify(orderPublisher).publish(any());
        verify(reservationService, never()).rollback(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishFailureKeepsReservationForScheduledRepublish() {
        CacheClient cacheClient = mock(CacheClient.class);
        ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        VoucherOrderPublisher orderPublisher = mock(VoucherOrderPublisher.class);
        SeckillReservationService reservationService = mock(SeckillReservationService.class);
        VoucherOrderServiceImpl service = service(
                cacheClient, seckillVoucherService, redisIdWorker, redisTemplate, orderPublisher, reservationService
        );
        saveLoginUser();
        when(cacheClient.queryWithLogicalExpire(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(activeVoucher());
        when(redisIdWorker.nextId("order")).thenReturn(100L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        doThrow(new IllegalStateException("broker unavailable")).when(orderPublisher).publish(any());

        Result result = service.seckillVoucher(12L);

        assertTrue(result.getSuccess());
        verify(reservationService, never()).rollback(any(), any(), any());
    }

    private VoucherOrderServiceImpl service(CacheClient cacheClient,
                                            ISeckillVoucherService seckillVoucherService,
                                            RedisIdWorker redisIdWorker,
                                            StringRedisTemplate redisTemplate,
                                            VoucherOrderPublisher orderPublisher,
                                            SeckillReservationService reservationService) {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "orderPublisher", orderPublisher);
        ReflectionTestUtils.setField(service, "reservationService", reservationService);
        return service;
    }

    private SeckillVoucher activeVoucher() {
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(12L);
        voucher.setStock(1000);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(1));
        voucher.setEndTime(LocalDateTime.now().plusMinutes(30));
        return voucher;
    }

    private void saveLoginUser() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }
}
