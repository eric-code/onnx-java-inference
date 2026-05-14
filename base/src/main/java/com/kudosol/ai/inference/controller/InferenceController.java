package com.kudosol.ai.inference.controller;

import com.kudosol.ai.inference.config.ApiKeyFilter;
import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.engine.InferenceEngine;
import com.kudosol.ai.inference.engine.ModelContainer;
import com.kudosol.ai.inference.engine.ModelManager;
import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.exception.ForbiddenException;
import com.kudosol.ai.inference.exception.PayloadTooLargeException;
import com.kudosol.ai.inference.exception.UnauthorizedException;
import com.kudosol.ai.inference.protocol.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class InferenceController {

    private final InferenceEngine inferenceEngine;
    private final ModelManager modelManager;
    private final InferenceProperties properties;

    private static final String MODEL_NAME_PATTERN = "[a-zA-Z0-9_.\\-]+";

    @PostMapping(value = "/infer/{modelName}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<Map<String, Object>> infer(@PathVariable String modelName,
                                                   @RequestBody byte[] inputData,
                                                  @RequestParam Map<String, Object> params,
                                                  HttpServletRequest request) {
        validateModelName(modelName);
        if (inputData == null || inputData.length == 0) {
            throw new BadRequestException("请求体不能为空");
        }
        if (inputData.length > properties.getMaxRequestSize()) {
            throw new PayloadTooLargeException(
                    "请求体大小 %d 超过限制 %d".formatted(inputData.length, properties.getMaxRequestSize()));
        }
        checkModelAccess(modelName, request);
        return ApiResponse.ok(inferenceEngine.infer(modelName, inputData, params));
    }

    @GetMapping("/models")
    public ApiResponse<List<Map<String, String>>> listModels() {
        List<Map<String, String>> models = modelManager.getModels().values().stream()
                .map(c -> Map.of("name", c.getName(), "version", c.getVersion()))
                .toList();
        return ApiResponse.ok(models);
    }

    @GetMapping("/models/{name}")
    public ApiResponse<Map<String, Object>> getModelDetail(@PathVariable String name,
                                                           HttpServletRequest request) {
        validateModelName(name);
        checkModelAccess(name, request);
        ModelContainer container = modelManager.getModel(name);
        Map<String, Object> detail = new HashMap<>();
        detail.put("name", container.getName());
        detail.put("version", container.getVersion());
        detail.put("inputs", container.getSession().getInputNames());
        detail.put("outputs", container.getSession().getOutputNames());
        return ApiResponse.ok(detail);
    }

    private void validateModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new BadRequestException("模型名称不能为空");
        }
        if (!modelName.matches(MODEL_NAME_PATTERN)) {
            throw new BadRequestException("模型名称格式非法，仅允许字母、数字、下划线、点号和连字符");
        }
    }

    private void checkModelAccess(String modelName, HttpServletRequest request) {
        ModelContainer container = modelManager.getModel(modelName);
        List<String> modelKeys = container.getApiKeys();
        if (modelKeys == null || modelKeys.isEmpty()) return;

        String apiKey = (String) request.getAttribute(ApiKeyFilter.API_KEY_ATTR);
        if (apiKey == null) {
            throw new UnauthorizedException("访问模型 '%s' 需要 API Key".formatted(modelName));
        }
        if (!modelKeys.contains(apiKey)) {
            throw new ForbiddenException("API Key 无权访问模型: " + modelName);
        }
    }
}
