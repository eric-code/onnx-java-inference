package com.kudosol.ai.inference.sample.step;

import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;
import com.kudosol.ai.inference.step.StepContextSupport;

import java.util.HashMap;
import java.util.Map;

/**
 * Sigmoid 激活函数步骤：对每个元素计算 1 / (1 + exp(-x))。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（可选）— 数据来源字段名；当模型只有 1 个 output 时省略，默认为唯一 output 名</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - step: sigmoid                    # 单输出模型可省略 params
 *   - step: sigmoid
 *     params: { field: variable }    # 多输出时显式声明
 * </pre>
 */
public class SigmoidStep implements Step {

    @Override
    public String name() {
        return "sigmoid";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = StepContextSupport.resolveOutputField(input, params, "sigmoid");

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
