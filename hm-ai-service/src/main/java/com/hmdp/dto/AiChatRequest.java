package com.hmdp.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "AI 客服聊天请求")
public class AiChatRequest {

    @Schema(description = "用户输入的问题", example = "这家店有什么推荐？")
    private String message;

    @Schema(description = "关联商铺 id，可为空", example = "1")
    private Long shopId;

    @Schema(description = "会话标识，首次不传时由服务端创建")
    private String conversationId;
}
