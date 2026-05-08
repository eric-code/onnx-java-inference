package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.Map;

public class Threshold implements Operator {

    @Override
    public String name() {
        return "threshold";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new IllegalArgumentException("threshold 缺少 field 参数");

        Object thresholdObj = params.get("value");
        if (thresholdObj == null) throw new IllegalArgumentException("threshold 缺少 value 参数");
        double threshold = ((Number) thresholdObj).doubleValue();

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        int[] result = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] >= threshold ? 1 : 0;
        }

        return Map.of(field, result);
    }
}
