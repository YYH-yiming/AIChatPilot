package com.yyh.knowledge.controller;

import com.yyh.common.result.Result;
import com.yyh.knowledge.dto.KnowledgeBaseCreateRequest;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.dto.KnowledgeSearchRequest;
import com.yyh.knowledge.dto.KnowledgeBaseVO;
import com.yyh.knowledge.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/knowledge/bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "创建知识库")
    @PostMapping
    public Result<KnowledgeBaseVO> create(@RequestBody @Valid KnowledgeBaseCreateRequest request) {
        return Result.success(knowledgeBaseService.create(request));
    }

    @Operation(summary = "查询当前租户知识库列表")
    @GetMapping
    public Result<List<KnowledgeBaseVO>> list() {
        return Result.success(knowledgeBaseService.listCurrentTenant());
    }

    @Operation(summary = "查询知识库详情")
    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> get(@PathVariable Long id) {
        return Result.success(knowledgeBaseService.getById(id));
    }

    @Operation(summary = "更新知识库")
    @PutMapping("/{id}")
    public Result<KnowledgeBaseVO> update(@PathVariable Long id,
                                          @RequestBody @Valid KnowledgeBaseCreateRequest request) {
        return Result.success(knowledgeBaseService.update(id, request));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.success();
    }

    @Operation(summary = "检索知识库切片")
    @PostMapping("/{id}/search")
    public Result<List<KnowledgeSearchHitVO>> search(@PathVariable Long id,
                                                     @RequestBody @Valid KnowledgeSearchRequest request) {
        return Result.success(knowledgeBaseService.search(id, request));
    }
}
