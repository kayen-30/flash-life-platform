package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginInterceptorTest {

    private final LoginInterceptor loginInterceptor = new LoginInterceptor();

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void preHandleBlocksWhenSessionHasNoUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = loginInterceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void preHandleSavesUserToThreadLocalWhenSessionHasUser() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setNickName("user_1");
        session.setAttribute("user", user);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = loginInterceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertSame(user, UserHolder.getUser());
    }

    @Test
    void afterCompletionClearsThreadLocal() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);

        loginInterceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object(),
                null
        );

        assertEquals(null, UserHolder.getUser());
    }
}
