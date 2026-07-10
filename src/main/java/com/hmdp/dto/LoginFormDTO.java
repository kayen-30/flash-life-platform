package com.hmdp.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录表单")
public class LoginFormDTO {
    @Schema(description = "手机号", example = "13800138000")
    private String phone;

    @Schema(description = "短信验证码", example = "123456")
    private String code;

    @Schema(description = "密码登录预留字段", example = "123456")
    private String password;
}
