package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.List;
import java.util.Map;

public class LabelMap implements Operator {

    @Override
    public String name() {
        return "label_map";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new IllegalArgumentException("label_map 缺少 field 参数");

        Object labelsObj = params.get("labels");
        if (labelsObj == null) throw new IllegalArgumentException("label_map 缺少 labels 参数");
        String[] labels = ((List<String>) labelsObj).toArray(new String[0]);

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        int[] indices = ArrayUtils.toIntArray(value);
        String[] result = new String[indices.length];
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] < 0 || indices[i] >= labels.length) {
                throw new IllegalArgumentException("索引 " + indices[i] + " 超出标签范围 [0, " + labels.length + ")");
            }
            result[i] = labels[indices[i]];
        }

        return Map.of(field, result);
    }
}
