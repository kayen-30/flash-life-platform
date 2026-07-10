package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
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
    void seckillUsesCachedVoucherMetadataWithoutQueryingDatabase() {
        CacheClient cacheClient = mock(CacheClient.class);
        ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(12L);
        voucher.setStock(1000);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(1));
        voucher.setEndTime(LocalDateTime.now().plusMinutes(30));
        when(cacheClient.queryWithLogicalExpire(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(voucher);
        when(redisIdWorker.nextId("order")).thenReturn(100L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        Result result = service.seckillVoucher(12L);

        assertTrue(result.getSuccess());
        verify(seckillVoucherService, never()).getById(12L);
    }
}
