package com.kudosol.ai.inference.operator.builtin;

import com.kudosol.ai.inference.operator.ArrayUtils;
import com.kudosol.ai.inference.operator.Operator;

import java.util.HashMap;
import java.util.Map;

public class TopK implements Operator {

    @Override
    public String name() {
        return "top_k";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) throw new IllegalArgumentException("top_k 缺少 field 参数");

        Object kObj = params.get("k");
        if (kObj == null) throw new IllegalArgumentException("top_k 缺少 k 参数");
        int k = ((Number) kObj).intValue();

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        long[] shape = ArrayUtils.inferShape(value);

        float[] values;
        int[] indices;

        if (shape.length >= 2) {
            int rows = (int) shape[0];
            int cols = (int) shape[1];
            values = new float[rows * k];
            indices = new int[rows * k];
            for (int i = 0; i < rows; i++) {
                int[] rowIdx = topKIndices(data, i * cols, cols, k);
                for (int j = 0; j < k; j++) {
                    values[i * k + j] = (float) data[i * cols + rowIdx[j]];
                    indices[i * k + j] = rowIdx[j];
                }
            }
        } else {
            int[] idx = topKIndices(data, 0, data.length, k);
            values = new float[k];
            indices = idx;
            for (int i = 0; i < k; i++) values[i] = (float) data[idx[i]];
        }

        Map<String, Object> result = new HashMap<>();
        result.put(field, values);
        result.put(field + "_indices", indices);
        return result;
    }

    private int[] topKIndices(double[] data, int offset, int len, int k) {
        int[] idx = new int[len];
        for (int i = 0; i < len; i++) idx[i] = i;
        for (int i = 0; i < k; i++) {
            int maxPos = i;
            for (int j = i + 1; j < len; j++) {
                if (data[offset + idx[j]] > data[offset + idx[maxPos]]) maxPos = j;
            }
            int tmp = idx[i];
            idx[i] = idx[maxPos];
            idx[maxPos] = tmp;
        }
        int[] result = new int[k];
        System.arraycopy(idx, 0, result, 0, k);
        return result;
    }
}
