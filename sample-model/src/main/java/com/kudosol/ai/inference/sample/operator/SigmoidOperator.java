package com.kudosol.ai.inference.sample.operator;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;
import com.kudosol.ai.inference.operator.OperatorContextSupport;

import java.util.HashMap;
import java.util.Map;

/**
 * Sigmoid 激活函数算子：对每个元素计算 1 / (1 + exp(-x))。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（可选）— 数据来源字段名；当模型只有 1 个 output 时省略，默认为唯一 output 名</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: sigmoid                    # 单输出模型可省略 params
 *   - op: sigmoid
 *     params: { field: variable }    # 多输出时显式声明
 * </pre>
 */
public class SigmoidOperator implements Operator {

    @Override
    public String name() {
        return "sigmoid";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = OperatorContextSupport.resolveOutputField(input, params, "sigmoid");

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
