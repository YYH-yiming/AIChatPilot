package com.yyh.knowledge.service;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentParseService {
    String parse(MultipartFile file);
}
