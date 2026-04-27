package com.yyh.knowledge.controller;

import com.yyh.common.result.Result;
import com.yyh.knowledge.dto.ChunkVO;
import com.yyh.knowledge.dto.DocumentUploadResponse;
import com.yyh.knowledge.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "知识文档管理")
@RestController
@RequestMapping("/api/knowledge/bases/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "上传文档")
    @PostMapping("/upload")
    public Result<DocumentUploadResponse> upload(@PathVariable Long kbId,
                                                 @RequestPart("file") MultipartFile file) {
        return Result.success(documentService.uploadDocument(kbId, file));
    }

    @Operation(summary = "查询知识库文档列表")
    @GetMapping
    public Result<List<DocumentUploadResponse>> list(@PathVariable Long kbId) {
        return Result.success(documentService.listDocuments(kbId));
    }

    @Operation(summary = "查询文档处理状态")
    @GetMapping("/{docId}")
    public Result<DocumentUploadResponse> get(@PathVariable Long kbId,
                                              @PathVariable Long docId) {
        return Result.success(documentService.getDocument(kbId, docId));
    }

    @Operation(summary = "查询文档切片列表")
    @GetMapping("/{docId}/chunks")
    public Result<List<ChunkVO>> chunks(@PathVariable Long kbId,
                                        @PathVariable Long docId) {
        return Result.success(documentService.listChunks(kbId, docId));
    }
}
