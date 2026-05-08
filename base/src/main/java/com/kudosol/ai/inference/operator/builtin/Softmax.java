package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.Map;

public class Softmax implements Operator {

    @Override
    public String name() {
        return "softmax";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new IllegalArgumentException("softmax 缺少 field 参数");

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        long[] shape = ArrayUtils.inferShape(value);
        double[] data = ArrayUtils.flattenToDouble(value);

        if (shape.length >= 2) {
            int rows = (int) shape[0];
            int cols = (int) shape[1];
            float[][] result = new float[rows][cols];
            for (int i = 0; i < rows; i++) {
                double maxVal = Double.NEGATIVE_INFINITY;
                for (int j = 0; j < cols; j++) {
                    if (data[i * cols + j] > maxVal) maxVal = data[i * cols + j];
                }
                double sum = 0;
                for (int j = 0; j < cols; j++) {
                    result[i][j] = (float) Math.exp(data[i * cols + j] - maxVal);
                    sum += result[i][j];
                }
                for (int j = 0; j < cols; j++) {
                    result[i][j] /= sum;
                }
            }
            return Map.of(field, result);
        } else {
            float[] result = new float[data.length];
            double maxVal = Double.NEGATIVE_INFINITY;
            for (double d : data) if (d > maxVal) maxVal = d;
            double sum = 0;
            for (int i = 0; i < data.length; i++) {
                result[i] = (float) Math.exp(data[i] - maxVal);
                sum += result[i];
            }
            for (int i = 0; i < result.length; i++) result[i] /= sum;
            return Map.of(field, result);
        }
    }
}
