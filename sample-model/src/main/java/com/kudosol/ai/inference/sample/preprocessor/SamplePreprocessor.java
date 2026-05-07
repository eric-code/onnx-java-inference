package com.kudosol.ai.inference.sample.preprocessor;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.Preprocessor;
import com.kudosol.ai.inference.spi.TensorMeta;
import java.nio.FloatBuffer;
import java.util.Map;

/**
 * XGBoost 模型前处理器：将输入 JSON 中的 float 特征转为 OnnxTensor，支持批量输入。
 * 通过 ModelMeta 动态获取输入名称和特征维度，无需硬编码。
 *
 * 输入格式：[[f1,f2,...,fN], [f1,f2,...,fN], ...]
 * 单条输入：[f1,f2,...,fN]
 */
public class SamplePreprocessor implements Preprocessor {

    private String inputName;
    private int featureCount;

    @Override
    public void init(ModelMeta meta) {
        TensorMeta input = meta.getInputs().get(0);
        this.inputName = input.getName();
        // shape 中最后一个维度为特征数，支持动态 batch
        this.featureCount = input.getShape().getLast().intValue();
    }

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
        float[] values = new float[batchSize * featureCount];

        for (int i = 0; i < batchSize; i++) {
            String[] parts = rows[i].split(",");
            if (parts.length != featureCount) {
                throw new IllegalArgumentException(
                        "每条数据需要 %d 个特征值，第 %d 条实际收到: %d".formatted(featureCount, i + 1, parts.length));
            }
            for (int j = 0; j < featureCount; j++) {
                values[i * featureCount + j] = Float.parseFloat(parts[j].trim());
            }
        }

        long[] shape = {batchSize, featureCount};
        OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values), shape);
        return Map.of(inputName, tensor);
    }
}
