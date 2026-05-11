package com.kudosol.ai.inference.step;

import ai.onnxruntime.OnnxTensor;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.PipelineStep;
import com.kudosol.ai.inference.spi.Preprocessor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PipelinePreprocessor implements Preprocessor {

    private final List<PipelineStep> steps;
    private final StepRegistry registry;
    private final PipelineExecutor executor;
    private ModelMeta meta;

    public PipelinePreprocessor(List<PipelineStep> steps, StepRegistry registry) {
        this.steps = steps;
        this.registry = registry;
        this.executor = new PipelineExecutor();
        this.executor.validate(this.executor.normalize(steps), registry);
    }

    @Override
    public void init(ModelMeta meta) {
        this.meta = meta;
    }

    @Override
    public Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) throws Exception {
        Map<String, Object> context = new ConcurrentHashMap<>();
        context.put("_raw", inputData);
        context.put("_params", params);
        if (meta != null) context.put(StepContextSupport.META_KEY, meta);

        try {
            executor.execute(steps, context, registry);
        } catch (Exception e) {
            closeTensorsInContext(context);
            throw e;
        }

        Map<String, OnnxTensor> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, Object>> it = context.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            if (entry.getValue() instanceof OnnxTensor tensor) {
                result.put(entry.getKey(), tensor);
                it.remove();
            }
        }

        if (result.isEmpty()) {
            throw new IllegalStateException("管线执行后上下文中无 OnnxTensor，请检查 to_tensor 步骤");
        }

        return result;
    }

    private void closeTensorsInContext(Map<String, Object> context) {
        for (Object value : context.values()) {
            if (value instanceof OnnxTensor tensor) {
                try {
                    tensor.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
