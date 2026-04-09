package com.yyh.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserInfoResponse {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String role;
    private Long tenantId;
    private Integer status;
    private LocalDateTime createdAt;
}
