package com.kudosol.ai.inference.spi;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置通用前处理器：根据 ModelMeta.inputs 将 JSON 请求体转为 OnnxTensor。
 * 输入格式：{"input_name": [values...], ...}，支持 batch 和多输入。
 */
public class DefaultPreprocessor implements Preprocessor {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModelMeta meta;

    @Override
    public void init(ModelMeta meta) {
        this.meta = meta;
    }

    @Override
    public Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) throws Exception {
        JsonNode root = objectMapper.readTree(inputData);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        Map<String, OnnxTensor> result = new LinkedHashMap<>();

        for (TensorMeta tensorMeta : meta.getInputs()) {
            String name = tensorMeta.getName();
            JsonNode node = root.get(name);
            if (node == null) {
                throw new IllegalArgumentException("输入中缺少字段: " + name);
            }
            result.put(name, buildTensor(env, tensorMeta, node));
        }
        return result;
    }

    private OnnxTensor buildTensor(OrtEnvironment env, TensorMeta tensorMeta, JsonNode node) throws OrtException {
        long[] shape = resolveShape(tensorMeta, node);
        String type = tensorMeta.getType();

        return switch (type) {
            case "float32" -> {
                float[] data = parseFloats(node);
                yield OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
            }
            case "int64" -> {
                long[] data = parseLongs(node);
                yield OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape);
            }
            case "int32" -> {
                int[] data = parseInts(node);
                yield OnnxTensor.createTensor(env, IntBuffer.wrap(data), shape);
            }
            default -> throw new IllegalArgumentException("不支持的输入类型: " + type);
        };
    }

    private long[] resolveShape(TensorMeta tensorMeta, JsonNode node) {
        long[] shape = tensorMeta.getShape().stream().mapToLong(Long::longValue).toArray();

        if (node.isArray() && node.size() > 0 && node.get(0).isArray()) {
            int batchSize = node.size();
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] <= 0) {
                    shape[i] = batchSize;
                    break;
                }
            }
        } else {
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] <= 0) shape[i] = 1;
            }
        }
        return shape;
    }

    private float[] parseFloats(JsonNode node) {
        if (node.isArray() && node.size() > 0 && node.get(0).isArray()) {
            int rows = node.size();
            int cols = node.get(0).size();
            float[] data = new float[rows * cols];
            for (int i = 0; i < rows; i++) {
                JsonNode row = node.get(i);
                for (int j = 0; j < cols; j++) {
                    data[i * cols + j] = (float) row.get(j).asDouble();
                }
            }
            return data;
        }
        float[] data = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            data[i] = (float) node.get(i).asDouble();
        }
        return data;
    }

    private long[] parseLongs(JsonNode node) {
        if (node.isArray() && node.size() > 0 && node.get(0).isArray()) {
            int rows = node.size();
            int cols = node.get(0).size();
            long[] data = new long[rows * cols];
            for (int i = 0; i < rows; i++) {
                JsonNode row = node.get(i);
                for (int j = 0; j < cols; j++) {
                    data[i * cols + j] = row.get(j).asLong();
                }
            }
            return data;
        }
        long[] data = new long[node.size()];
        for (int i = 0; i < node.size(); i++) {
            data[i] = node.get(i).asLong();
        }
        return data;
    }

    private int[] parseInts(JsonNode node) {
        if (node.isArray() && node.size() > 0 && node.get(0).isArray()) {
            int rows = node.size();
            int cols = node.get(0).size();
            int[] data = new int[rows * cols];
            for (int i = 0; i < rows; i++) {
                JsonNode row = node.get(i);
                for (int j = 0; j < cols; j++) {
                    data[i * cols + j] = row.get(j).asInt();
                }
            }
            return data;
        }
        int[] data = new int[node.size()];
        for (int i = 0; i < node.size(); i++) {
            data[i] = node.get(i).asInt();
        }
        return data;
    }
}
