package com.yyh.knowledge.service.impl;

import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import com.yyh.knowledge.service.DocumentParseService;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class DocumentParseServiceImpl implements DocumentParseService {

    @Override
    public String parse(MultipartFile file) {
        if (isPlainText(file.getOriginalFilename())) {
            try {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "文本文件读取失败: " + ex.getMessage());
            }
        }

        try (InputStream inputStream = file.getInputStream()) {
            return parse(file.getOriginalFilename(), inputStream);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文档解析失败: " + ex.getMessage());
        }
    }

    @Override
    public String parse(String filename, InputStream inputStream) {
        if (isPlainText(filename)) {
            try {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "文本文件读取失败: " + ex.getMessage());
            }
        }

        try {
            AutoDetectParser parser = new AutoDetectParser();
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            parser.parse(inputStream, handler, metadata, new ParseContext());
            return handler.toString();
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文档解析失败: " + ex.getMessage());
        }
    }

    private boolean isPlainText(String filename) {
        if (filename == null) {
            return false;
        }
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        return lowerFilename.endsWith(".txt")
                || lowerFilename.endsWith(".md")
                || lowerFilename.endsWith(".markdown")
                || lowerFilename.endsWith(".csv")
                || lowerFilename.endsWith(".json");
    }
}
