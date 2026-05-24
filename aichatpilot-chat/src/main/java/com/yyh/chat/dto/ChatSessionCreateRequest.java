package com.yyh.chat.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ChatSessionCreateRequest {

    private String title;

    @Pattern(regexp = "knowledge|agent", message = "mode仅支持knowledge或agent")
    private String mode;

    private Long kbId;
}
