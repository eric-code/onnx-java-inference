package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;

import java.util.Map;

/**
 * 数值四舍五入，支持指定小数位数。
 *
 * <p>结果写回原字段。默认 decimals=0 为取整。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（必填）— 要操作的字段名</li>
 *   <li>{@code decimals}（可选，默认 0）— 保留小数位数</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: round
 *     params: { field: score, decimals: 2 }
 * </pre>
 */
public class Round implements Step {

    @Override
    public String name() {
        return "round";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new IllegalArgumentException("round 缺少 field 参数");

        int decimals = params.containsKey("decimals") ? ((Number) params.get("decimals")).intValue() : 0;

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        double factor = Math.pow(10, decimals);
        for (int i = 0; i < data.length; i++) {
            data[i] = Math.round(data[i] * factor) / factor;
        }

        return Map.of(field, data);
    }
}
