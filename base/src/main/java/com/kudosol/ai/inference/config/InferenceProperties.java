package com.kudosol.ai.inference.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "inference")
public class InferenceProperties {

    private String modelDir = "/models";

    private int threadCount = Runtime.getRuntime().availableProcessors();

    private List<String> modelSources = List.of();

    public void setModelSources(List<String> modelSources) {
        this.modelSources = (modelSources != null) ? modelSources : List.of();
    }

    private long maxRequestSize = 50 * 1024 * 1024;

    private int maxConcurrentInferences = Runtime.getRuntime().availableProcessors();

    private Duration downloadTimeout = Duration.ofMinutes(5);

    private int downloadRetryCount = 2;

    private Duration downloadRetryDelay = Duration.ofSeconds(3);

    private Duration inferenceTimeout = Duration.ofSeconds(60);
}
