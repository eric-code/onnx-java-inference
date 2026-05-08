package com.kudosol.ai.inference.operator.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kudosol.ai.inference.operator.Operator;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ParseJson implements Operator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "parse_json";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        byte[] raw = (byte[]) input.get("_raw");
        if (raw == null) {
            throw new IllegalStateException("上下文中缺少 _raw 数据");
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (!root.isObject()) {
                throw new IllegalArgumentException("请求体必须是 JSON 对象");
            }
            Map<String, Object> result = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                result.put(entry.getKey(), MAPPER.treeToValue(entry.getValue(), Object.class));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
