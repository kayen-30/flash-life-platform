package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 复杂问题的执行计划；计划本身是模型生成的辅助数据，最终仍由 Agent 工具预算约束。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {

    private String reasoning;
    private List<Step> steps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step {
        private Integer step;
        private String tool;
        private String purpose;
    }
}
