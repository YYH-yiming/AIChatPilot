package com.yyh.knowledge.service;

import com.yyh.knowledge.dto.ChunkVO;
import com.yyh.knowledge.dto.DocumentUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {
    DocumentUploadResponse uploadDocument(Long kbId, MultipartFile file);
    DocumentUploadResponse reprocessDocument(Long kbId, Long docId);
    DocumentUploadResponse getDocument(Long kbId, Long docId);
    List<DocumentUploadResponse> listDocuments(Long kbId);
    List<ChunkVO> listChunks(Long kbId, Long docId);
}
