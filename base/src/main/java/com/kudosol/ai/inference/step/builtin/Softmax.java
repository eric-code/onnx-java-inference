package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;

import java.util.Map;

/**
 * Softmax 概率转换，使用数值稳定算法（减去行最大值后求 exp）。
 *
 * <p>2D 输入 [batch, classes] → 逐行 softmax → 返回 float[batch][classes]。
 * 1D 输入 [classes] → 返回 float[classes]。
 * 结果写回原字段。
 *
 * <p>参数：{@code field}（必填）— 要操作的字段名
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: softmax
 *     params: { field: logits }
 * </pre>
 */
public class Softmax implements Step {

    @Override
    public String name() {
        return "softmax";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new BadRequestException("softmax 缺少 field 参数");

        Object value = input.get(field);
        if (value == null) throw new BadRequestException("字段 " + field + " 不存在");

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
