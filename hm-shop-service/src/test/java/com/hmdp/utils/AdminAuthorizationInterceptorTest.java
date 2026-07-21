package com.hmdp.utils;

import com.hmdp.auth.RequireAdmin;
import com.hmdp.auth.UserRoles;
import com.hmdp.controller.ShopController;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthorizationInterceptorTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void normalUserCannotCreateShop() throws Exception {
        Method method = ShopController.class.getMethod("saveShop", Shop.class);
        assertTrue(method.isAnnotationPresent(RequireAdmin.class));
        HandlerMethod handlerMethod = new HandlerMethod(new ShopController(), method);
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setRole(UserRoles.USER);
        UserHolder.saveUser(user);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = new AdminAuthorizationInterceptor().preHandle(
                new MockHttpServletRequest(), response, handlerMethod
        );

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }
}
