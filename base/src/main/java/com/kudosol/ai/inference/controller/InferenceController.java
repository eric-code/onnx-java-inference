package com.kudosol.ai.inference.controller;

import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.engine.InferenceEngine;
import com.kudosol.ai.inference.engine.ModelContainer;
import com.kudosol.ai.inference.engine.ModelManager;
import com.kudosol.ai.inference.exception.PayloadTooLargeException;
import com.kudosol.ai.inference.protocol.ApiResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InferenceController {

    private final InferenceEngine inferenceEngine;
    private final ModelManager modelManager;
    private final InferenceProperties properties;

    @PostMapping(value = "/infer/{modelName}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<Map<String, Object>> infer(@PathVariable String modelName,
                                                   @RequestBody byte[] inputData,
                                                   @RequestParam Map<String, Object> params) {
        if (inputData.length > properties.getMaxRequestSize()) {
            throw new PayloadTooLargeException(
                    "请求体大小 %d 超过限制 %d".formatted(inputData.length, properties.getMaxRequestSize()));
        }
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
    public ApiResponse<Map<String, Object>> getModelDetail(@PathVariable String name) {
        ModelContainer container = modelManager.getModel(name);
        Map<String, Object> detail = new HashMap<>();
        detail.put("name", container.getName());
        detail.put("version", container.getVersion());
        detail.put("inputs", container.getSession().getInputNames());
        detail.put("outputs", container.getSession().getOutputNames());
        return ApiResponse.ok(detail);
    }
}
