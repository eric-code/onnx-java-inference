package com.kudosol.ai.inference.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "inference.s3")
public class S3Properties {

    private boolean enabled = false;

    /**
     * S3 兼容端点（MinIO 等），为空则用 AWS 默认端点
     */
    private String endpoint;

    private String region = "us-east-1";

    private String bucket;

    private String accessKey;

    private String secretKey;

    /**
     * Path style access（MinIO 需要设为 true）
     */
    private boolean pathStyleAccess = false;
}
