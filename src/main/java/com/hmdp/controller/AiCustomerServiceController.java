package com.hmdp.controller;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiCustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/customer-service")
@Tag(name = "AI 客服接口", description = "智能客服聊天接口")
public class AiCustomerServiceController {

    @Resource
    private IAiCustomerService aiCustomerService;

    /**
     * 智能客服聊天入口，内部组合 RAG 知识召回和业务工具调用。
     */
    @PostMapping("/chat")
    @Operation(summary = "智能客服聊天", description = "接收用户问题，结合 RAG 知识召回和业务工具生成回复。")
    public Result chat(@RequestBody AiChatRequest request) {
        return aiCustomerService.chat(request);
    }
}
