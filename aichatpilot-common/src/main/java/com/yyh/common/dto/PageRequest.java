package com.yyh.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

@Data
public class PageRequest implements Serializable {

    @Min(value = 1, message = "页码最小为1")
    private int pageNum = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 100, message = "每页条数最大为100")
    private int pageSize = 10;
}
