package com.kudosol.ai.inference.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import com.kudosol.ai.inference.log.InferenceLog;
import com.kudosol.ai.inference.log.InferenceLogPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class InferenceEngine {

    private final ModelManager modelManager;
    private final InferenceLogPublisher logPublisher;

    public Map<String, Object> infer(String modelName, byte[] inputData, Map<String, Object> params) {
        ModelContainer container;
        try {
            container = modelManager.getModel(modelName);
        } catch (IllegalArgumentException e) {
            logPublisher.publish(InferenceLog.error(modelName, "lookup", "模型不存在", e.getMessage()));
            throw e;
        }
        long totalStart = System.currentTimeMillis();

        // 前处理
        Map<String, OnnxTensor> inputs;
        long preprocessStart = System.currentTimeMillis();
        try {
            inputs = container.getPreprocessor().process(inputData, params);
            logPublisher.publish(InferenceLog.info(modelName, "preprocess", "前处理完成",
                    System.currentTimeMillis() - preprocessStart));
        } catch (Exception e) {
            logPublisher.publish(InferenceLog.error(modelName, "preprocess", "前处理失败", e.getMessage()));
            throw new RuntimeException("模型 [%s] 前处理失败: %s".formatted(modelName, e.getMessage()), e);
        }

        // 推理 + 后处理
        long inferStart = System.currentTimeMillis();
        // OrtSession.Result 关闭时输出张量也会关闭，所以后处理必须在 result 关闭前完成
        try (OrtSession.Result result = container.getSession().run(inputs)) {
            logPublisher.publish(InferenceLog.info(modelName, "infer", "推理完成",
                    System.currentTimeMillis() - inferStart));

            Map<String, OnnxTensor> outputs = new HashMap<>();
            for (String name : container.getSession().getOutputNames()) {
                OnnxTensor tensor = (OnnxTensor) result.get(name).orElseThrow();
                outputs.put(name, tensor);
            }

            long postprocessStart = System.currentTimeMillis();
            try {
                Map<String, Object> postResult = container.getPostprocessor().process(outputs);
                logPublisher.publish(InferenceLog.info(modelName, "postprocess", "后处理完成",
                        System.currentTimeMillis() - postprocessStart));
                logPublisher.publish(InferenceLog.info(modelName, "total", "推理全流程完成",
                        System.currentTimeMillis() - totalStart));
                return postResult;
            } catch (Exception e) {
                logPublisher.publish(InferenceLog.error(modelName, "postprocess", "后处理失败", e.getMessage()));
                throw new RuntimeException("模型 [%s] 后处理失败: %s".formatted(modelName, e.getMessage()), e);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re && re.getMessage() != null && re.getMessage().contains("后处理失败")) {
                throw re;
            }
            logPublisher.publish(InferenceLog.error(modelName, "infer", "推理执行失败", e.getMessage()));
            throw new RuntimeException("模型 [%s] 推理执行失败: %s".formatted(modelName, e.getMessage()), e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }
}
