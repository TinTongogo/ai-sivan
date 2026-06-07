package com.icusu.sivan.web.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 修改密码请求 DTO。
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "旧密码不能为空")
    @ToString.Exclude
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 128, message = "密码长度需在 6-128 个字符之间")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$", message = "密码必须包含大小写字母和数字")
    @ToString.Exclude
    private String newPassword;
}
