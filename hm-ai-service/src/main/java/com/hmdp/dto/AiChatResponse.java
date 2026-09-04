package com.hmdp.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI 客服回复")
public class AiChatResponse {

    @Schema(description = "当前会话标识")
    private String conversationId;

    @Schema(description = "客服回复内容")
    private String answer;

    @Schema(description = "本次命中的知识来源")
    private List<String> sources;
}
