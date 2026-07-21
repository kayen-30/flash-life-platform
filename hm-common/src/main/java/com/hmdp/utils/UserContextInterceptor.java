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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(GatewayHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            return true;
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
