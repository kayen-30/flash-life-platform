package com.hmdp.api.dto;

import lombok.Data;

@Data
public class VoucherSummaryDTO {
    private Long id;
    private String title;
    private String subTitle;
    private String rules;
    private Long payValue;
    private Long actualValue;
    private Integer stock;
}
