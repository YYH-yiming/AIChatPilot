package com.yyh.knowledge.minio;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public String upload(Long kbId, String originalFilename, InputStream inputStream, long size, String contentType) {
        String objectName = buildObjectName(kbId, originalFilename);
        try {
            ensureBucketExists();
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build()
            );
            return minioProperties.getEndpoint() + "/" + minioProperties.getBucketName() + "/" + objectName;
        } catch (Exception ex) {
            throw new IllegalStateException("文件上传 MinIO 失败", ex);
        }
    }

    public void deleteByUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        String prefix = minioProperties.getEndpoint() + "/" + minioProperties.getBucketName() + "/";
        if (!fileUrl.startsWith(prefix)) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(fileUrl.substring(prefix.length()))
                    .build());
        } catch (Exception ex) {
            log.warn("删除 MinIO 文件失败: url={}, message={}", fileUrl, ex.getMessage());
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.getBucketName()).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioProperties.getBucketName()).build());
        }
    }

    private String buildObjectName(Long kbId, String originalFilename) {
        String safeFilename = originalFilename == null ? "unknown" : originalFilename.replace(" ", "_");
        return "knowledge/" + kbId + "/" + LocalDate.now() + "/" + UUID.randomUUID() + "-" + safeFilename;
    }
}
