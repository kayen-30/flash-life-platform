package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;

class BlogServiceImplTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateLikeDoesNotIncrementDatabaseCounterAgain() {
        BlogMapper blogMapper = mock(BlogMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score(BLOG_LIKED_KEY + 10L, "7")).thenReturn(null);
        when(blogMapper.insertBlogLike(10L, 7L)).thenReturn(0);
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);

        Result result = service.likeBlog(10L);

        assertTrue(result.getSuccess());
        verify(blogMapper, never()).incrementLiked(10L);
        verify(zSetOperations).add(eq(BLOG_LIKED_KEY + 10L), eq("7"), anyDouble());
    }
}
