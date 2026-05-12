package com.kudosol.ai.inference.sample.step;

import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;
import com.kudosol.ai.inference.step.StepContextSupport;

import java.util.HashMap;
import java.util.Map;

/**
 * 对数变换前处理步骤：log(1 + x)
 * <p>
 * 用于对偏态分布的输入特征做平滑处理，常见于金融风控、计数特征等场景。
 * 单输出模型可省略 field 参数。
 */
public class LogTransformStep implements Step {

    @Override
    public String name() {
        return "log_transform";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = StepContextSupport.resolveInputField(input, params, name());

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        for (int i = 0; i < data.length; i++) {
            data[i] = Math.log1p(data[i]);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(field, data);
        return result;
    }
}
