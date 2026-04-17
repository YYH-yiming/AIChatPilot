package com.yyh.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeBaseCreateRequest {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 200, message = "知识库名称不能超过200个字符")
    private String name;

    @Size(max = 2000, message = "知识库描述不能超过2000个字符")
    private String description;

    @Size(max = 100, message = "Embedding模型名称不能超过100个字符")
    private String embeddingModel;
}
