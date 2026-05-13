package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;

import java.util.Map;

/**
 * 二值化阈值分类，将数值数组按阈值转为 0/1 整数数组。
 *
 * <p>值 >= threshold → 1，否则 → 0。结果写回原字段。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（必填）— 要操作的字段名</li>
 *   <li>{@code value}（必填）— 阈值</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: threshold
 *     params: { field: score, value: 0.5 }
 * </pre>
 */
public class Threshold implements Step {

    @Override
    public String name() {
        return "threshold";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new BadRequestException("threshold 缺少 field 参数");

        Object thresholdObj = params.get("value");
        if (thresholdObj == null) throw new BadRequestException("threshold 缺少 value 参数");
        double threshold = ((Number) thresholdObj).doubleValue();

        Object value = input.get(field);
        if (value == null) throw new BadRequestException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        int[] result = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] >= threshold ? 1 : 0;
        }

        return Map.of(field, result);
    }
}
