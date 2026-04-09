package com.yyh.user.service;

import com.yyh.user.dto.*;

public interface UserService {

    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    UserInfoResponse getUserInfo(Long userId);
}
