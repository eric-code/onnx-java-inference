package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;

import java.util.Map;

/**
 * 类型转换，将数值数组转为指定的目标类型，结果写回原字段。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（必填）— 要转换的字段名</li>
 *   <li>{@code to}（必填）— 目标类型："float32"、"int64"、"int32"</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: cast
 *     params: { field: ids, to: int64 }
 * </pre>
 */
public class Cast implements Step {

    @Override
    public String name() {
        return "cast";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        String to = (String) params.get("to");
        if (field == null) throw new IllegalArgumentException("cast 缺少 field 参数");
        if (to == null) throw new IllegalArgumentException("cast 缺少 to 参数");

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        Object result = switch (to) {
            case "float32" -> ArrayUtils.flattenToFloat(value);
            case "int64" -> ArrayUtils.flattenToLong(value);
            case "int32" -> ArrayUtils.flattenToInt(value);
            default -> throw new IllegalArgumentException("不支持的类型: " + to);
        };
        return Map.of(field, result);
    }
}
