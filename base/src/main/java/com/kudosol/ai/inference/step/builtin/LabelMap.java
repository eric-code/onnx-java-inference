package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;

import java.util.List;
import java.util.Map;

/**
 * 将整数索引映射为标签字符串，常用于分类结果解码。
 *
 * <p>输入 int[]/long[] 索引数组 → 输出 String[] 标签数组。
 * 索引超出 labels 范围会抛异常。结果写回原字段。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（必填）— 要操作的字段名</li>
 *   <li>{@code labels}（必填）— 标签字符串列表，如 ["cat", "dog", "bird"]</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: label_map
 *     params: { field: output, labels: ["setosa", "versicolor", "virginica"] }
 * </pre>
 */
public class LabelMap implements Step {

    @Override
    public String name() {
        return "label_map";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new BadRequestException("label_map 缺少 field 参数");

        Object labelsObj = params.get("labels");
        if (labelsObj == null) throw new BadRequestException("label_map 缺少 labels 参数");
        String[] labels = ((List<String>) labelsObj).toArray(new String[0]);

        Object value = input.get(field);
        if (value == null) throw new BadRequestException("字段 " + field + " 不存在");

        int[] indices = ArrayUtils.toIntArray(value);
        String[] result = new String[indices.length];
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] < 0 || indices[i] >= labels.length) {
                throw new BadRequestException("索引 " + indices[i] + " 超出标签范围 [0, " + labels.length + ")");
            }
            result[i] = labels[indices[i]];
        }

        return Map.of(field, result);
    }
}
