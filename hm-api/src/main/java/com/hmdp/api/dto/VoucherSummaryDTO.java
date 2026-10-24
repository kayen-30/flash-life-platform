package com.hmdp.api.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class VoucherSummaryDTO {
    private Long id;
    private String title;
    private String subTitle;
    private String rules;
    private Long payValue;
    private Long actualValue;
    private Integer stock;
    private Integer type;
    private Integer status;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    // 交易服务根据查询时事实给出状态；缺字段时不得推断可购买。
    private String availability;
    // 使用星期与购买状态分开；未知或未确认政策不能解释成无限制。
    private WeekdayCheck weekdayCheck;

    @Data
    public static class WeekdayCheck {
        private Integer requestedWeekday;
        private String verdict;
        private String reason;
        private String policyVersion;
        private List<Integer> allowedWeekdays;
    }
}
