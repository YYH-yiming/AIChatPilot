package com.yyh.user.service;

import com.yyh.user.entity.Tenant;

public interface TenantService {

    Tenant getById(Long id);

    void update(Tenant tenant);
}
