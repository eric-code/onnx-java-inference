package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.Map;

public class Normalize implements Operator {

    @Override
    public String name() {
        return "normalize";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) {
            throw new IllegalArgumentException("normalize 缺少 field 参数");
        }
        Object value = input.get(field);
        if (value == null) {
            throw new IllegalArgumentException("字段 " + field + " 不存在");
        }

        String method = (String) params.getOrDefault("method", "standard");
        double[] data = ArrayUtils.flattenToDouble(value);
        long[] shape = ArrayUtils.inferShape(value);
        int featureDim = (shape.length >= 2) ? (int) shape[shape.length - 1] : data.length;

        switch (method) {
            case "standard" -> {
                double[] mean = ArrayUtils.toDoubleArray(params.get("mean"));
                double[] std = ArrayUtils.toDoubleArray(params.get("std"));
                for (int i = 0; i < data.length; i++) {
                    data[i] = (data[i] - mean[i % featureDim]) / std[i % featureDim];
                }
            }
            case "minmax" -> {
                double[] min = ArrayUtils.toDoubleArray(params.get("min"));
                double[] max = ArrayUtils.toDoubleArray(params.get("max"));
                for (int i = 0; i < data.length; i++) {
                    data[i] = (data[i] - min[i % featureDim]) / (max[i % featureDim] - min[i % featureDim]);
                }
            }
            default -> throw new IllegalArgumentException("不支持的归一化方法: " + method);
        }

        return Map.of(field, data);
    }
}
