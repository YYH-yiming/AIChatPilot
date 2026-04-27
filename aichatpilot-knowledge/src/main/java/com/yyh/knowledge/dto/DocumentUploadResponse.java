package com.yyh.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentUploadResponse {
    private Long docId;
    private Long kbId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String fileType;
    private Integer parseStatus;
    private Integer chunkCount;
    private String errorMsg;
    private String message;
    private LocalDateTime createdAt;
}
