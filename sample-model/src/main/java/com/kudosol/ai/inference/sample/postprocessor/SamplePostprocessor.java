package com.kudosol.ai.inference.sample.postprocessor;

import ai.onnxruntime.OnnxTensor;
import com.kudosol.ai.inference.spi.Postprocessor;
import java.util.HashMap;
import java.util.Map;

/**
 * XGBoost 模型后处理器：将输出张量转为预测结果，支持批量输出。
 * 模型输出：variable, shape=[?,1], type=float32
 */
public class SamplePostprocessor implements Postprocessor {

    @Override
    public Map<String, Object> process(Map<String, OnnxTensor> output) throws Exception {
        Map<String, Object> result = new HashMap<>();
        OnnxTensor variable = output.get("variable");
        float[][] values = (float[][]) variable.getValue();
        float[] predictions = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            predictions[i] = values[i][0];
        }
        result.put("variable", predictions);
        return result;
    }
}
