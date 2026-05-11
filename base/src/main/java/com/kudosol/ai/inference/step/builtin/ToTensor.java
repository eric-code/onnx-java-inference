package com.kudosol.ai.inference.step.builtin;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.TensorMeta;
import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;
import com.kudosol.ai.inference.step.StepContextSupport;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将数值数组转为 OnnxTensor，写入上下文以 ONNX input 名为 key，并移除源 field 键。
 *
 * <p>name / type / shape 全部从 ONNX 模型元数据自动推断，yml 中不允许手工配置。
 * shape 中的动态维（-1 / 0）按实际数据推断。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code field}（可选）— 数据来源字段名；当模型只有 1 个 input 时省略，默认为唯一 input 名</li>
 * </ul>
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: to_tensor                     # 单输入模型可省略 params
 *   - op: to_tensor
 *     params: { field: float_input }    # 多输入或字段名与 ONNX input 名不同时显式声明
 * </pre>
 */
public class ToTensor implements Step {

    @Override
    public String name() {
        return "to_tensor";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = StepContextSupport.resolveInputField(input, params, "to_tensor");

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        ModelMeta meta = StepContextSupport.meta(input);
        TensorMeta tensorMeta = StepContextSupport.findInput(meta, field);
        String type = tensorMeta.getType();

        long[] shape = parseShape(tensorMeta.getShape(), value);
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
                case "string" -> OnnxTensor.createTensor(env,
                        ArrayUtils.flattenToString(value), shape);
                default -> throw new IllegalArgumentException("不支持的张量类型: " + type);
            };
        } catch (OrtException e) {
            throw new RuntimeException("创建张量失败: " + e.getMessage(), e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(field, tensor);
        return result;
    }

    @SuppressWarnings("unchecked")
    private long[] parseShape(Object shapeObj, Object value) {
        if (shapeObj instanceof List<?> list) {
            long[] shape = list.stream().mapToLong(v -> ((Number) v).longValue()).toArray();

            int dynamicCount = 0;
            long knownProduct = 1;
            int dynamicIndex = -1;
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] <= 0) {
                    dynamicCount++;
                    dynamicIndex = i;
                } else {
                    knownProduct *= shape[i];
                }
            }

            if (dynamicCount == 1) {
                long totalElements = ArrayUtils.countElements(value);
                shape[dynamicIndex] = totalElements / knownProduct;
            } else if (dynamicCount > 1) {
                long[] inferred = ArrayUtils.inferShape(value);
                for (int i = 0; i < shape.length; i++) {
                    if (shape[i] <= 0 && i < inferred.length) {
                        shape[i] = inferred[i];
                    }
                }
            }

            return shape;
        }
        return ArrayUtils.inferShape(value);
    }
}
