package com.kudosol.ai.inference.controller;

import com.kudosol.ai.inference.config.ApiKeyFilter;
import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.engine.InferenceEngine;
import com.kudosol.ai.inference.engine.ModelContainer;
import com.kudosol.ai.inference.engine.ModelManager;
import com.kudosol.ai.inference.exception.ForbiddenException;
import com.kudosol.ai.inference.exception.PayloadTooLargeException;
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

    @PostMapping(value = "/infer/{modelName}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<Map<String, Object>> infer(@PathVariable String modelName,
                                                   @RequestBody byte[] inputData,
                                                  @RequestParam Map<String, Object> params,
                                                  HttpServletRequest request) {
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
        checkModelAccess(name, request);
        ModelContainer container = modelManager.getModel(name);
        Map<String, Object> detail = new HashMap<>();
        detail.put("name", container.getName());
        detail.put("version", container.getVersion());
        detail.put("inputs", container.getSession().getInputNames());
        detail.put("outputs", container.getSession().getOutputNames());
        return ApiResponse.ok(detail);
    }

    private void checkModelAccess(String modelName, HttpServletRequest request) {
        String apiKey = (String) request.getAttribute(ApiKeyFilter.API_KEY_ATTR);
        if (apiKey == null) return;

        ModelContainer container = modelManager.getModel(modelName);

        boolean modelYmlAllows = checkModelYmlAccess(container, apiKey);
        boolean globalMappingAllows = checkGlobalMappingAccess(modelName, apiKey);

        boolean hasModelLevelConfig = container.getApiKeys() != null && !container.getApiKeys().isEmpty();
        boolean hasGlobalMapping = !properties.getApiKeyModels().isEmpty();

        if (!hasModelLevelConfig && !hasGlobalMapping) return;

        if (hasModelLevelConfig && hasGlobalMapping) {
            if (!modelYmlAllows || !globalMappingAllows) {
                throw new ForbiddenException("API Key 无权访问模型: " + modelName);
            }
        } else if (hasModelLevelConfig && !modelYmlAllows) {
            throw new ForbiddenException("API Key 无权访问模型: " + modelName);
        } else if (hasGlobalMapping && !globalMappingAllows) {
            throw new ForbiddenException("API Key 无权访问模型: " + modelName);
        }
    }

    private boolean checkModelYmlAccess(ModelContainer container, String apiKey) {
        List<String> modelKeys = container.getApiKeys();
        if (modelKeys == null || modelKeys.isEmpty()) return true;
        return modelKeys.contains(apiKey);
    }

    private boolean checkGlobalMappingAccess(String modelName, String apiKey) {
        Map<String, List<String>> mapping = properties.getApiKeyModels();
        if (mapping.isEmpty()) return true;
        List<String> allowedModels = mapping.get(apiKey);
        if (allowedModels == null) return false;
        return allowedModels.contains(modelName);
    }
}
