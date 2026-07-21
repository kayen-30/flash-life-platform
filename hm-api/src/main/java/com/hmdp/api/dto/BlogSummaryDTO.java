package com.hmdp.api.dto;

import lombok.Data;

@Data
public class BlogSummaryDTO {
    private Long id;
    private Long shopId;
    private String title;
    private String content;
    private Integer liked;
}
