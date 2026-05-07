package com.kudosol.ai.inference.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
