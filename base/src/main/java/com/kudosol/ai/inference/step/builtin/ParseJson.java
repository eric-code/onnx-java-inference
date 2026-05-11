package com.kudosol.ai.inference.step.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kudosol.ai.inference.step.Step;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 解析原始请求体为 JSON 结构化数据，将顶层 key 展平写入上下文。
 *
 * <p>通常作为预处理管线的第一步，从上下文的 {@code _raw} 字段读取请求体 byte[]，
 * 解析后将每个顶层字段直接写入上下文（展平模式）。
 *
 * <p>参数：无
 *
 * <p>示例：请求体 {@code {"features": [1.0, 2.0], "label": "a"}}
 * → 上下文变为 {@code {features: [1.0, 2.0], label: "a"}}
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: parse_json
 * </pre>
 */
public class ParseJson implements Step {

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
