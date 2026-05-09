package com.kudosol.ai.inference.operator;

import ai.onnxruntime.OnnxTensor;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.PipelineStep;
import com.kudosol.ai.inference.spi.Postprocessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PipelinePostprocessor implements Postprocessor {

    private final List<PipelineStep> steps;
    private final OperatorRegistry registry;
    private final PipelineExecutor executor;
    private ModelMeta meta;

    public PipelinePostprocessor(List<PipelineStep> steps, OperatorRegistry registry) {
        this.steps = steps;
        this.registry = registry;
        this.executor = new PipelineExecutor();
        if (!steps.isEmpty()) {
            this.executor.validate(this.executor.normalize(steps), registry);
        }
    }

    @Override
    public void init(ModelMeta meta) {
        this.meta = meta;
    }

    @Override
    public Map<String, Object> process(Map<String, OnnxTensor> output) throws Exception {
        Map<String, Object> context = new ConcurrentHashMap<>();

        for (Map.Entry<String, OnnxTensor> entry : output.entrySet()) {
            context.put(entry.getKey(), entry.getValue().getValue());
        }
        if (meta != null) context.put(OperatorContextSupport.META_KEY, meta);

        executor.execute(steps, context, registry);

        context.remove(OperatorContextSupport.META_KEY);
        return new LinkedHashMap<>(context);
    }
}
