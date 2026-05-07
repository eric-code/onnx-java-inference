package com.kudosol.ai.inference.sample.postprocessor;

import ai.onnxruntime.OnnxTensor;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.Postprocessor;
import com.kudosol.ai.inference.spi.TensorMeta;
import java.util.HashMap;
import java.util.Map;

/**
 * XGBoost 模型后处理器：将输出张量转为预测结果，支持批量输出。
 * 通过 ModelMeta 动态获取输出名称，无需硬编码。
 */
public class SamplePostprocessor implements Postprocessor {

    private String outputName;

    @Override
    public void init(ModelMeta meta) {
        TensorMeta output = meta.getOutputs().get(0);
        this.outputName = output.getName();
    }

    @Override
    public Map<String, Object> process(Map<String, OnnxTensor> output) throws Exception {
        Map<String, Object> result = new HashMap<>();
        OnnxTensor tensor = output.get(outputName);
        float[][] values = (float[][]) tensor.getValue();
        float[] predictions = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            predictions[i] = values[i][0];
        }
        result.put(outputName, predictions);
        return result;
    }
}
