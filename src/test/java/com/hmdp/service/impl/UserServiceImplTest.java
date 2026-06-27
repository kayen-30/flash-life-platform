package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceImplTest {

    @Test
    void sendCodeRejectsInvalidPhone() {
        UserServiceImpl userService = new UserServiceImpl();
        MockHttpSession session = new MockHttpSession();

        Result result = userService.sendCode("123", session);

        assertFalse(result.getSuccess());
        assertEquals("\u624b\u673a\u53f7\u683c\u5f0f\u9519\u8bef", result.getErrorMsg());
        assertNull(session.getAttribute("code"));
    }

    @Test
    void sendCodeStoresSixDigitCodeInSession() {
        UserServiceImpl userService = new UserServiceImpl();
        MockHttpSession session = new MockHttpSession();

        Result result = userService.sendCode("13800138000", session);

        assertTrue(result.getSuccess());
        Object code = session.getAttribute("code");
        assertNotNull(code);
        assertTrue(code.toString().matches("\\d{6}"));
    }

    @Test
    void loginRejectsMismatchedCode() {
        UserServiceImpl userService = new UserServiceImpl();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("code", "123456");
        LoginFormDTO loginForm = loginForm("13800138000", "654321");

        Result result = userService.login(loginForm, session);

        assertFalse(result.getSuccess());
        assertEquals("\u9a8c\u8bc1\u7801\u9519\u8bef", result.getErrorMsg());
        assertNull(session.getAttribute("user"));
    }

    @Test
    void loginExistingUserStoresUserDtoInSession() {
        UserServiceImpl userService = spy(new UserServiceImpl());
        User existingUser = new User()
                .setPhone("13800138000")
                .setNickName("\u5c0f\u9c7c");
        existingUser.setId(7L);
        doReturn(existingUser).when(userService).getOne(any(Wrapper.class));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("code", "123456");
        LoginFormDTO loginForm = loginForm("13800138000", "123456");

        Result result = userService.login(loginForm, session);

        assertTrue(result.getSuccess());
        UserDTO user = (UserDTO) session.getAttribute("user");
        assertNotNull(user);
        assertEquals(7L, user.getId());
        assertEquals("\u5c0f\u9c7c", user.getNickName());
        verify(userService, never()).save(any(User.class));
    }

    @Test
    void loginCreatesMissingUserAndStoresUserDtoInSession() {
        UserServiceImpl userService = spy(new UserServiceImpl());
        doReturn(null).when(userService).getOne(any(Wrapper.class));
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(8L);
            return true;
        }).when(userService).save(any(User.class));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("code", "123456");
        LoginFormDTO loginForm = loginForm("13800138000", "123456");

        Result result = userService.login(loginForm, session);

        assertTrue(result.getSuccess());
        UserDTO user = (UserDTO) session.getAttribute("user");
        assertNotNull(user);
        assertEquals(8L, user.getId());
        assertTrue(user.getNickName().startsWith("user_"));
        verify(userService).save(argThat(savedUser ->
                "13800138000".equals(savedUser.getPhone())
                        && savedUser.getNickName().startsWith("user_")));
    }

    private LoginFormDTO loginForm(String phone, String code) {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(phone);
        loginForm.setCode(code);
        return loginForm;
    }
}
