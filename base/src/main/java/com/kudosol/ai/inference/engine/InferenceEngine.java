package com.kudosol.ai.inference.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InferenceEngine {

    private final ModelManager modelManager;

    public Map<String, Object> infer(String modelName, byte[] inputData, Map<String, Object> params) {
        ModelContainer container = modelManager.getModel(modelName);

        // 前处理
        Map<String, OnnxTensor> inputs;
        try {
            inputs = container.getPreprocessor().process(inputData, params);
        } catch (Exception e) {
            throw new RuntimeException("模型 [%s] 前处理失败: %s".formatted(modelName, e.getMessage()), e);
        }

        // 推理 + 后处理
        // OrtSession.Result 关闭时输出张量也会关闭，所以后处理必须在 result 关闭前完成
        try (OrtSession.Result result = container.getSession().run(inputs)) {
            Map<String, OnnxTensor> outputs = new HashMap<>();
            for (String name : container.getSession().getOutputNames()) {
                OnnxTensor tensor = (OnnxTensor) result.get(name).orElseThrow();
                outputs.put(name, tensor);
            }

            try {
                return container.getPostprocessor().process(outputs);
            } catch (Exception e) {
                throw new RuntimeException("模型 [%s] 后处理失败: %s".formatted(modelName, e.getMessage()), e);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re && re.getMessage() != null && re.getMessage().contains("后处理失败")) {
                throw re;
            }
            throw new RuntimeException("模型 [%s] 推理执行失败: %s".formatted(modelName, e.getMessage()), e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }
}
