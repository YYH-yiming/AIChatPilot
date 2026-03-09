package com.yyh.common.constant;

public final class CommonConstant {

    private CommonConstant() {}

    /** JWT 请求头名称 */
    public static final String AUTH_HEADER = "Authorization";

    /** JWT Token 前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** JWT 默认过期时间（毫秒）：24小时 */
    public static final long TOKEN_EXPIRE_MS = 24 * 60 * 60 * 1000L;

    /** 用户ID在JWT Claims中的key */
    public static final String CLAIM_USER_ID = "userId";

    /** 租户ID在JWT Claims中的key */
    public static final String CLAIM_TENANT_ID = "tenantId";

    /** 默认分页大小 */
    public static final int DEFAULT_PAGE_SIZE = 10;
}
