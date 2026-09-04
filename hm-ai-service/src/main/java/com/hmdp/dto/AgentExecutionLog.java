package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 工具执行日志；thought 是基于工具用途生成的可展示说明，不是模型隐藏思维链。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionLog {

    /** 当前会话中的工具步骤序号。 */
    private Integer step;

    /** 根据工具用途生成的可视化说明。 */
    private String thought;

    /** 实际调用的工具名称。 */
    private String action;

    /** LangChain4j 传入的 JSON 参数。 */
    private String actionInput;

    /** 工具返回给 Agent 的文本。 */
    private String observation;

    /** 当前工具调用消耗的模型 Token；当前同步工具边界无法拆分模型 Token 时为空。 */
    private Integer tokenUsed;

    /** 工具执行耗时，单位为毫秒。 */
    private Long duration;

    /** SUCCESS / FAILED。 */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;

    /** 失败时的可展示错误信息。 */
    private String errorMessage;
}
