package com.yyh.analytics.support;

import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long currentTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或请求缺少租户上下文");
        }
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> map) {
            Object tenantId = map.get("tenantId");
            if (tenantId instanceof Long value) {
                return value;
            }
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或请求缺少租户上下文");
    }
}
