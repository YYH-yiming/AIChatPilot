package com.yyh.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeSearchRequest {

    @NotBlank(message = "检索问题不能为空")
    private String query;

    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK = 5;

    // 以下为评测/调试用的可选覆盖项，不传则使用全局配置默认值
    private Boolean denseEnabled;
    private Boolean sparseEnabled;
    private Boolean rerankEnabled;

    @Min(value = 1, message = "recallTopN最小为1")
    @Max(value = 100, message = "recallTopN最大为100")
    private Integer recallTopN;
}
