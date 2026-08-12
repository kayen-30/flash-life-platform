package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_COOLDOWN_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_COOLDOWN_SECONDS;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_ATTEMPT_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL_SECONDS;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void sendCodeRejectsInvalidPhone() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        UserServiceImpl userService = userService(redisTemplate);

        Result result = userService.sendCode("123");

        assertFalse(result.getSuccess());
        assertEquals("手机号格式错误", result.getErrorMsg());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendCodeStoresSixDigitCodeInRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                LOGIN_CODE_COOLDOWN_KEY + "13800138000",
                "1",
                LOGIN_CODE_COOLDOWN_SECONDS,
                TimeUnit.SECONDS
        )).thenReturn(true);
        UserServiceImpl userService = userService(redisTemplate);

        Result result = userService.sendCode("13800138000");

        assertTrue(result.getSuccess());
        verify(valueOperations).set(
                eq(LOGIN_CODE_KEY + "13800138000"),
                argThat(code -> code.matches("\\d{6}")),
                eq(LOGIN_CODE_TTL),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendCodeRejectsRequestsDuringCooldown() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                LOGIN_CODE_COOLDOWN_KEY + "13800138000",
                "1",
                LOGIN_CODE_COOLDOWN_SECONDS,
                TimeUnit.SECONDS
        )).thenReturn(false);

        Result result = userService(redisTemplate).sendCode("13800138000");

        assertFalse(result.getSuccess());
        assertEquals("验证码发送过于频繁，请稍后再试", result.getErrorMsg());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginRejectsMismatchedCode() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(LOGIN_ATTEMPT_KEY + "13800138000")).thenReturn(1L);
        when(valueOperations.get(LOGIN_CODE_KEY + "13800138000")).thenReturn("123456");
        UserServiceImpl userService = userService(redisTemplate);

        Result result = userService.login(loginForm("13800138000", "654321"));

        assertFalse(result.getSuccess());
        assertEquals("验证码错误", result.getErrorMsg());
        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginRejectsAttemptsBeyondLimitBeforeCheckingCode() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(LOGIN_ATTEMPT_KEY + "13800138000")).thenReturn(6L);

        Result result = userService(redisTemplate).login(loginForm("13800138000", "123456"));

        assertFalse(result.getSuccess());
        assertEquals("验证码尝试次数过多，请重新获取", result.getErrorMsg());
        verify(valueOperations, never()).get(LOGIN_CODE_KEY + "13800138000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginExistingUserStoresTokenInRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.increment(LOGIN_ATTEMPT_KEY + "13800138000")).thenReturn(1L);
        when(valueOperations.get(LOGIN_CODE_KEY + "13800138000")).thenReturn("123456");

        UserServiceImpl userService = spy(userService(redisTemplate));
        User existingUser = new User().setPhone("13800138000").setNickName("小鱼");
        existingUser.setId(7L);
        doReturn(existingUser).when(userService).getOne(any(Wrapper.class));

        Result result = userService.login(loginForm("13800138000", "123456"));

        assertTrue(result.getSuccess());
        String token = (String) result.getData();
        assertNotNull(token);
        verify(hashOperations).putAll(
                eq(LOGIN_USER_KEY + token),
                argThat(userMap -> "7".equals(userMap.get("id")) && "小鱼".equals(userMap.get("nickName")))
        );
        verify(redisTemplate).expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL_SECONDS, TimeUnit.SECONDS);
        verify(redisTemplate).delete(LOGIN_CODE_KEY + "13800138000");
        verify(userService, never()).save(any(User.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginCreatesMissingUserAndStoresTokenInRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.increment(LOGIN_ATTEMPT_KEY + "13800138000")).thenReturn(1L);
        when(valueOperations.get(LOGIN_CODE_KEY + "13800138000")).thenReturn("123456");

        UserServiceImpl userService = spy(userService(redisTemplate));
        doReturn(null).when(userService).getOne(any(Wrapper.class));
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(8L);
            return true;
        }).when(userService).save(any(User.class));

        Result result = userService.login(loginForm("13800138000", "123456"));

        assertTrue(result.getSuccess());
        String token = (String) result.getData();
        assertNotNull(token);
        verify(hashOperations).putAll(
                eq(LOGIN_USER_KEY + token),
                argThat(userMap -> "8".equals(userMap.get("id")))
        );
        verify(userService).save(argThat(savedUser ->
                "13800138000".equals(savedUser.getPhone())
                        && savedUser.getNickName().startsWith("user_")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void signMarksCurrentDayInMonthlyBitmap() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UserServiceImpl userService = userService(redisTemplate);
        saveLoginUser(7L);
        LocalDateTime now = LocalDateTime.now();
        String key = USER_SIGN_KEY + 7L + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));

        Result result = userService.sign();

        assertTrue(result.getSuccess());
        verify(valueOperations).setBit(key, now.getDayOfMonth() - 1L, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void signCountReturnsConsecutiveDaysEndingToday() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.bitField(any(), any(BitFieldSubCommands.class)))
                .thenReturn(List.of(7L));
        UserServiceImpl userService = userService(redisTemplate);
        saveLoginUser(7L);

        Result result = userService.signCount();

        assertEquals(3, result.getData());
    }

    @Test
    @SuppressWarnings("unchecked")
    void signCountReturnsZeroWhenMonthlyBitmapIsMissing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.bitField(any(), any(BitFieldSubCommands.class)))
                .thenReturn(Collections.emptyList());
        UserServiceImpl userService = userService(redisTemplate);
        saveLoginUser(7L);

        Result result = userService.signCount();

        assertEquals(0, result.getData());
    }

    private UserServiceImpl userService(StringRedisTemplate redisTemplate) {
        UserServiceImpl userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", redisTemplate);
        return userService;
    }

    private LoginFormDTO loginForm(String phone, String code) {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(phone);
        loginForm.setCode(code);
        return loginForm;
    }

    private void saveLoginUser(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }
}
