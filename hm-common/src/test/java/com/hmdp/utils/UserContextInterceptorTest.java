package com.hmdp.utils;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextInterceptorTest {

    private static final String INTERNAL_TOKEN = "trusted-internal-token";

    @AfterEach
    void clearUserContext() {
        UserHolder.removeUser();
    }

    @Test
    void trustedGatewayHeadersShouldRestoreUserContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GatewayHeaders.USER_ID, "42");
        request.addHeader(GatewayHeaders.USER_ROLE, "ADMIN");
        request.addHeader(GatewayHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN);

        boolean passed = new UserContextInterceptor(INTERNAL_TOKEN)
                .preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(passed).isTrue();
        assertThat(UserHolder.getUser().getId()).isEqualTo(42L);
        assertThat(UserHolder.getUser().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void untrustedUserHeaderShouldNotRestoreUserContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(GatewayHeaders.USER_ID, "42");

        boolean passed = new UserContextInterceptor(INTERNAL_TOKEN)
                .preHandle(request, response, new Object());

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(UserHolder.getUser()).isNull();
    }
}
