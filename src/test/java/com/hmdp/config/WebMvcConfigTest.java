package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMvcConfigTest {

    @Test
    void loginInterceptorBeanTypeIsCorrect() {
        WebMvcConfig config = new WebMvcConfig();

        assertTrue(config.loginInterceptor() instanceof LoginInterceptor);
    }

    @Test
    void publicEndpointsDoNotBypassAuthenticationForWritableGroups() {
        var publicEndpoints = Arrays.asList(WebMvcConfig.PUBLIC_ENDPOINTS);

        assertFalse(publicEndpoints.contains("/shop/**"));
        assertFalse(publicEndpoints.contains("/voucher/**"));
        assertFalse(publicEndpoints.contains("/upload/**"));
        assertTrue(publicEndpoints.contains("/shop/*"));
        assertTrue(publicEndpoints.contains("/voucher/list/*"));
    }
}
