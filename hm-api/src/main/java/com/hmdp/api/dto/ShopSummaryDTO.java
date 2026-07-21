package com.hmdp.api.dto;

import lombok.Data;

@Data
public class ShopSummaryDTO {
    private Long id;
    private String name;
    private String area;
    private String address;
    private Long avgPrice;
    private Integer score;
    private Integer sold;
    private Integer comments;
    private String openHours;
}
