package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;

import java.util.Map;

/**
 * 取最大值索引，常用于分类模型获取预测类别。
 *
 * <p>2D 输入 [batch, classes] → 对每行取 argmax → 返回 int[batch]。
 * 1D 输入 [classes] → 返回 int[1]。
 * 结果写回原字段。
 *
 * <p>参数：{@code field}（必填）— 要操作的字段名
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: argmax
 *     params: { field: output }
 * </pre>
 */
public class ArgMax implements Step {

    @Override
    public String name() {
        return "argmax";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new BadRequestException("argmax 缺少 field 参数");

        Object value = input.get(field);
        if (value == null) throw new BadRequestException("字段 " + field + " 不存在");

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
