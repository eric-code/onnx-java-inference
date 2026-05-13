package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;

import java.util.Map;

/**
 * 数值归一化，支持 z-score (standard) 和 min-max 两种方法。
 *
 * <p>对指定字段的数值数组做归一化，结果写回原字段。支持 1D 和 2D 数组，
 * mean/std/min/max 参数按最后一个维度广播（即每个特征对应一个参数值）。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（必填）— 要归一化的字段名</li>
 *   <li>{@code method}（默认 "standard"）— "standard" 或 "minmax"</li>
 *   <li>{@code mean}, {@code std} — standard 方法所需参数</li>
 *   <li>{@code min}, {@code max} — minmax 方法所需参数</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: normalize
 *     params: { field: features, method: minmax, min: [0.0, 0.0], max: [1.0, 1.0] }
 * </pre>
 */
public class Normalize implements Step {

    @Override
    public String name() {
        return "normalize";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) {
            throw new BadRequestException("normalize 缺少 field 参数");
        }
        Object value = input.get(field);
        if (value == null) {
            throw new BadRequestException("字段 " + field + " 不存在");
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
            default -> throw new BadRequestException("不支持的归一化方法: " + method);
        }

        return Map.of(field, data);
    }
}
