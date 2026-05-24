package com.yyh.chat.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChatSessionDetailVO {

    private ChatSessionVO session;
    private List<ChatMessageVO> messages = new ArrayList<>();
}
