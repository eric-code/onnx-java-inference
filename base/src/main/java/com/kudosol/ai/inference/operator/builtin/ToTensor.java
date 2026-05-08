package com.kudosol.ai.inference.operator.builtin;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将数值数组转为 OnnxTensor，写入上下文的 name 键，并移除源 field 键。
 *
 * <p>shape 参数中值为 -1 或 0 的维度会根据实际数据自动推断。
 * 例如 shape: [-1, 4]，输入 2 条数据 → 实际 shape 为 [2, 4]。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（必填）— 数据来源字段名</li>
 *   <li>{@code name}（必填）— OnnxTensor 在上下文中的 key，通常与模型输入名一致</li>
 *   <li>{@code type}（必填）— 张量类型："float32"、"int64"、"int32"</li>
 *   <li>{@code shape}（可选）— 张量形状列表，动态维度用 -1，省略则从数据推断</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: to_tensor
 *     params: { field: features, name: float_input, type: float32, shape: [-1, 4] }
 * </pre>
 */
public class ToTensor implements Operator {

    @Override
    public String name() {
        return "to_tensor";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        String name = (String) params.get("name");
        String type = (String) params.get("type");
        if (field == null) throw new IllegalArgumentException("to_tensor 缺少 field 参数");
        if (name == null) throw new IllegalArgumentException("to_tensor 缺少 name 参数");
        if (type == null) throw new IllegalArgumentException("to_tensor 缺少 type 参数");

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        long[] shape = parseShape(params.get("shape"), value);
        OrtEnvironment env = OrtEnvironment.getEnvironment();

        OnnxTensor tensor;
        try {
            tensor = switch (type) {
                case "float32" -> OnnxTensor.createTensor(env,
                        FloatBuffer.wrap(ArrayUtils.flattenToFloat(value)), shape);
                case "int64" -> OnnxTensor.createTensor(env,
                        LongBuffer.wrap(ArrayUtils.flattenToLong(value)), shape);
                case "int32" -> OnnxTensor.createTensor(env,
                        IntBuffer.wrap(ArrayUtils.flattenToInt(value)), shape);
                default -> throw new IllegalArgumentException("不支持的张量类型: " + type);
            };
        } catch (OrtException e) {
            throw new RuntimeException("创建张量失败: " + e.getMessage(), e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(name, tensor);
        return result;
    }

    @SuppressWarnings("unchecked")
    private long[] parseShape(Object shapeObj, Object value) {
        if (shapeObj instanceof List<?> list) {
            long[] shape = list.stream().mapToLong(v -> ((Number) v).longValue()).toArray();
            long[] inferred = ArrayUtils.inferShape(value);
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] <= 0 && i < inferred.length) {
                    shape[i] = inferred[i];
                }
            }
            return shape;
        }
        return ArrayUtils.inferShape(value);
    }
}
