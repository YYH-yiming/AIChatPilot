package com.yyh.chat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatSendMessageRequest {

    @NotBlank(message = "消息内容不能为空")
    private String content;

    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK;
}
