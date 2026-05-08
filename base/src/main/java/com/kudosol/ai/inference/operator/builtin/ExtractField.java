package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.Operator;

import java.util.Map;

public class ExtractField implements Operator {

    @Override
    public String name() {
        return "extract_field";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) {
            throw new IllegalArgumentException("extract_field 缺少 field 参数");
        }

        String[] parts = field.split("\\.");
        Object value = input;
        for (String part : parts) {
            if (value instanceof Map<?, ?> map) {
                value = map.get(part);
            } else {
                throw new IllegalArgumentException("路径 " + field + " 中 " + part + " 不是对象");
            }
        }
        if (value == null) {
            throw new IllegalArgumentException("字段 " + field + " 不存在");
        }

        String targetKey = parts[parts.length - 1];
        return Map.of(targetKey, value);
    }
}
