package com.hmdp.api;

import com.hmdp.dto.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "hm-user-service", configuration = InternalFeignConfiguration.class)
public interface UserClient {

    @PostMapping("/internal/users/summaries")
    List<UserDTO> queryUserSummaries(@RequestBody List<Long> userIds);
}
