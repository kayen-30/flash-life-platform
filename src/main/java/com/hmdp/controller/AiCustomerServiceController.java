package com.hmdp.controller;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiCustomerService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/customer-service")
public class AiCustomerServiceController {

    @Resource
    private IAiCustomerService aiCustomerService;

    /**
     * 智能客服聊天入口，内部组合 RAG 知识召回和业务工具调用。
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatRequest request) {
        return aiCustomerService.chat(request);
    }
}
