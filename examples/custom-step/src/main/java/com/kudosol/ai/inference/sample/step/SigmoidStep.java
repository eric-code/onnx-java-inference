package com.kudosol.ai.inference.sample.step;

import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;
import com.kudosol.ai.inference.step.StepContextSupport;

import java.util.HashMap;
import java.util.Map;

/**
 * Sigmoid 后处理步骤：1 / (1 + e^(-x))
 * <p>
 * 将模型输出映射到 [0, 1] 概率区间，常见于二分类场景。
 * 单输出模型可省略 field 参数。
 */
public class SigmoidStep implements Step {

    @Override
    public String name() {
        return "sigmoid";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = StepContextSupport.resolveOutputField(input, params, name());

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        for (int i = 0; i < data.length; i++) {
            data[i] = 1.0 / (1.0 + Math.exp(-data[i]));
        }

        Map<String, Object> result = new HashMap<>();
        result.put(field, data);
        return result;
    }
}
