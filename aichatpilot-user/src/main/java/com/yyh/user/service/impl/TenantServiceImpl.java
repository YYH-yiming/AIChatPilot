package com.yyh.user.service.impl;

import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import com.yyh.user.entity.Tenant;
import com.yyh.user.mapper.TenantMapper;
import com.yyh.user.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantMapper tenantMapper;

    @Override
    public Tenant getById(Long id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "租户不存在");
        }
        return tenant;
    }

    @Override
    public void update(Tenant tenant) {
        tenantMapper.updateById(tenant);
    }
}
