package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginInterceptorTest {

    private final LoginInterceptor loginInterceptor = new LoginInterceptor();

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void preHandleBlocksWhenThreadLocalHasNoUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = loginInterceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void preHandleAllowsUserLoadedByRefreshTokenInterceptor() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setNickName("user_1");
        UserHolder.saveUser(user);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = loginInterceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
    }
}
