package com.hmdp.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一接口返回结果")
public class Result {
    @Schema(description = "请求是否成功", example = "true")
    private Boolean success;

    @Schema(description = "失败时的错误信息", example = "手机号格式错误")
    private String errorMsg;

    @Schema(description = "业务数据，不同接口返回结构不同")
    private Object data;

    @Schema(description = "分页查询总数", example = "10")
    private Long total;

    public static Result ok(){
        return new Result(true, null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}
