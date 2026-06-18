package com.agenthub.ai.base.pojo.dto;

import lombok.Data;

/**
 * @Title: PasswardDTO
 * 
 * @Package com.agenthub.ai.pojo.dto
 * @Date
 * @description: 修改密码DTO
 */

@Data
public class PasswordDTO {
    private Integer id;
    private String oldPassword;
    private String newPassword;
    private String confirmPassword;
}
