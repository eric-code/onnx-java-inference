package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.Map;

public class Round implements Operator {

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
