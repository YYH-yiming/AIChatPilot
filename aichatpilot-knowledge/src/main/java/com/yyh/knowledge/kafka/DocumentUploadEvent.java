package com.yyh.knowledge.kafka;

import lombok.Data;

@Data
public class DocumentUploadEvent {
    private Long kbId;
    private Long docId;
}
