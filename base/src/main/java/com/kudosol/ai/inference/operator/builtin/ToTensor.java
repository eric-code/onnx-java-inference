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
        result.put(field, null);
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
