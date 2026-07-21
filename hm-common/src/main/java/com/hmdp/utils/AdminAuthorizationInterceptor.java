package com.hmdp.utils;

import com.hmdp.auth.RequireAdmin;
import com.hmdp.auth.UserRoles;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 对标记了 RequireAdmin 的控制器方法执行统一角色校验。
 */
public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || !handlerMethod.hasMethodAnnotation(RequireAdmin.class)) {
            return true;
        }

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
            return false;
        }
        if (!UserRoles.ADMIN.equals(user.getRole())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "无管理员权限");
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"errorMsg\":\"" + message + "\"}");
    }
}
