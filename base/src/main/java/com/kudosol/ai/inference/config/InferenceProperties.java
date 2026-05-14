package com.kudosol.ai.inference.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "inference")
public class InferenceProperties {

    private String modelDir = "/models";

    private int threadCount = Runtime.getRuntime().availableProcessors();

    private List<String> modelSources = List.of();

    private long maxRequestSize = 50 * 1024 * 1024;

    private int maxConcurrentInferences = Runtime.getRuntime().availableProcessors();

    private Duration downloadTimeout = Duration.ofMinutes(5);

    private int downloadRetryCount = 2;

    private Duration downloadRetryDelay = Duration.ofSeconds(3);

    private Duration inferenceTimeout = Duration.ofSeconds(60);

    private List<String> apiKeys = List.of();

    private Map<String, List<String>> apiKeyModels = Map.of();
}
