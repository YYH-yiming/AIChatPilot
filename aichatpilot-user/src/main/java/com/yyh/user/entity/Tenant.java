package com.yyh.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tenant")
public class Tenant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String apiKeyConfig;
    private String modelConfig;
    private Integer maxQps;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
