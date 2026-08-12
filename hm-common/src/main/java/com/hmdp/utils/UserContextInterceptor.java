package com.hmdp.utils;

import com.hmdp.auth.UserRoles;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 将网关传来的用户标识恢复到当前服务线程，兼容原有 UserHolder 业务代码。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    private final String internalToken;

    public UserContextInterceptor(String internalToken) {
        this.internalToken = internalToken;
    }

    /**
     * 仅把已由网关认证并附带服务间凭证的用户身份恢复到 UserHolder。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(GatewayHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            return true;
        }
        // 只有网关认证后补充的内部凭证才能使用户身份头生效，直连请求不能伪造登录态。
        if (!InternalTokenValidator.matches(internalToken, request.getHeader(GatewayHeaders.INTERNAL_TOKEN))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        try {
            UserDTO user = new UserDTO();
            user.setId(Long.valueOf(userId));
            String role = request.getHeader(GatewayHeaders.USER_ROLE);
            user.setRole(UserRoles.ADMIN.equals(role) ? UserRoles.ADMIN : UserRoles.USER);
            UserHolder.saveUser(user);
            return true;
        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
