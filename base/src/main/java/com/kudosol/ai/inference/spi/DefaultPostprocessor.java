package com.kudosol.ai.inference.spi;

import ai.onnxruntime.OnnxTensor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置通用后处理器：根据 ModelMeta.outputs 将 OnnxTensor 转为可序列化的结果。
 * 直接透传 OnnxTensor.getValue() 返回的 Java 多维数组。
 */
public class DefaultPostprocessor implements Postprocessor {

    private ModelMeta meta;

    @Override
    public void init(ModelMeta meta) {
        this.meta = meta;
    }

    @Override
    public Map<String, Object> process(Map<String, OnnxTensor> output) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (TensorMeta tensorMeta : meta.getOutputs()) {
            String name = tensorMeta.getName();
            OnnxTensor tensor = output.get(name);
            if (tensor == null) {
                continue;
            }
            result.put(name, tensor.getValue());
        }
        return result;
    }
}
