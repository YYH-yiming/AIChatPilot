package com.yyh.knowledge.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface DocumentParseService {
    String parse(MultipartFile file);
    String parse(String filename, InputStream inputStream);
}
