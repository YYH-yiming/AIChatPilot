package com.yyh.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long kbId;
    private String content;
    private Integer tokenCount;
    private Integer chunkIndex;
    private String vectorId;
    private Long parentId;
    private String chunkRole;
    private String metadata;
    private LocalDateTime createdAt;
}
