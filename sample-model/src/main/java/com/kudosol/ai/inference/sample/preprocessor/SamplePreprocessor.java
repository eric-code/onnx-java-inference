package com.kudosol.ai.inference.sample.preprocessor;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import com.kudosol.ai.inference.spi.Preprocessor;
import java.nio.FloatBuffer;
import java.util.Map;

/**
 * XGBoost 模型前处理器：将输入 JSON 中的 float 特征转为 OnnxTensor，支持批量输入。
 * 模型输入：float_input, shape=[?,4], type=float32
 *
 * 输入格式：[[f1,f2,f3,f4], [f1,f2,f3,f4], ...]
 * 单条输入：[f1,f2,f3,f4]
 */
public class SamplePreprocessor implements Preprocessor {

    private static final int FEATURE_COUNT = 4;

    @Override
    public Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) throws Exception {

        String stripped = new String(inputData).trim();
        while (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1).trim();
        }

        String[] rows = stripped.split("],\\s*\\[");
        if (rows.length == 0) {
            throw new IllegalArgumentException("输入数据为空");
        }

        int batchSize = rows.length;
        float[] values = new float[batchSize * FEATURE_COUNT];

        for (int i = 0; i < batchSize; i++) {
            String[] parts = rows[i].split(",");
            if (parts.length != FEATURE_COUNT) {
                throw new IllegalArgumentException(
                        "每条数据需要 %d 个特征值，第 %d 条实际收到: %d".formatted(FEATURE_COUNT, i + 1, parts.length));
            }
            for (int j = 0; j < FEATURE_COUNT; j++) {
                values[i * FEATURE_COUNT + j] = Float.parseFloat(parts[j].trim());
            }
        }

        long[] shape = {batchSize, FEATURE_COUNT};
        OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values), shape);
        return Map.of("float_input", tensor);
    }
}
