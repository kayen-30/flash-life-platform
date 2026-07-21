package com.hmdp.controller;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 向其他服务提供最小化的公开用户资料，禁止返回手机号和密码字段。
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    @Resource
    private IUserService userService;

    @PostMapping("/summaries")
    public List<UserDTO> queryUserSummaries(@RequestBody List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        return userService.listByIds(userIds).stream()
                .map(this::toUserDTO)
                .toList();
    }

    private UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setNickName(user.getNickName());
        dto.setIcon(user.getIcon());
        return dto;
    }
}
