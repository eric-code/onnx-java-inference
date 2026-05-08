package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.Map;

public class Cast implements Operator {

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
