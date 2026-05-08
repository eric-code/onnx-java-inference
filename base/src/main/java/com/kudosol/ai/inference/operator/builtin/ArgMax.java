package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.Map;

public class ArgMax implements Operator {

    @Override
    public String name() {
        return "argmax";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new IllegalArgumentException("argmax 缺少 field 参数");

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        long[] shape = ArrayUtils.inferShape(value);
        double[] data = ArrayUtils.flattenToDouble(value);

        int[] result;
        if (shape.length >= 2) {
            int rows = (int) shape[0];
            int cols = (int) shape[1];
            result = new int[rows];
            for (int i = 0; i < rows; i++) {
                int maxIdx = 0;
                double maxVal = data[i * cols];
                for (int j = 1; j < cols; j++) {
                    if (data[i * cols + j] > maxVal) {
                        maxVal = data[i * cols + j];
                        maxIdx = j;
                    }
                }
                result[i] = maxIdx;
            }
        } else {
            int maxIdx = 0;
            double maxVal = data[0];
            for (int i = 1; i < data.length; i++) {
                if (data[i] > maxVal) {
                    maxVal = data[i];
                    maxIdx = i;
                }
            }
            result = new int[]{maxIdx};
        }

        return Map.of(field, result);
    }
}
