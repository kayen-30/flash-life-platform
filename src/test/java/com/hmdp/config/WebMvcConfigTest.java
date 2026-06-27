package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMvcConfigTest {

    @Test
    void loginInterceptorBeanTypeIsCorrect() {
        WebMvcConfig config = new WebMvcConfig();

        assertTrue(config.loginInterceptor() instanceof LoginInterceptor);
    }
}
