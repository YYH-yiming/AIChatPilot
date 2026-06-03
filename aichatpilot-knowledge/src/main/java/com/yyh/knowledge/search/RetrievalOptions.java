package com.yyh.knowledge.search;

/**
 * 检索按请求覆盖项。字段为 null 时回退到全局配置默认值。
 * 用于评测时按 arm（dense-only / sparse-only / hybrid / hybrid+rerank）切换，无需重启服务。
 */
public record RetrievalOptions(Boolean dense, Boolean sparse, Boolean rerank, Integer recallTopN) {

    public static RetrievalOptions none() {
        return new RetrievalOptions(null, null, null, null);
    }
}
