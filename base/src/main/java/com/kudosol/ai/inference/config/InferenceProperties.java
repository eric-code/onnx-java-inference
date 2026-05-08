package com.kudosol.ai.inference.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "inference")
public class InferenceProperties {

    /**
     * 模型根目录，默认 /models
     */
    private String modelDir = "/models";

    /**
     * 全局推理线程池大小，默认 CPU 核心数
     */
    private int threadCount = Runtime.getRuntime().availableProcessors();

    /**
     * 模型来源列表，支持 s3:// 和 https:// 两种 URI scheme。
     * s3://key/path.tar.gz — 通过 S3Client 下载（需配置 inference.s3 连接信息）
     * https://... 或 http://... — 通过 HTTP 下载
     */
    private List<String> modelSources = List.of();
}
