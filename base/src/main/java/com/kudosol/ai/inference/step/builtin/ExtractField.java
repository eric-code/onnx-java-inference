package com.kudosol.ai.inference.step.builtin;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.step.Step;

import java.util.Map;

/**
 * 从上下文中提取指定字段，支持点号路径访问嵌套对象。
 *
 * <p>两种使用场景：
 * <ul>
 *   <li>嵌套路径提取：{@code field: payload.features} 从 {@code {payload: {features: [...]}}}
 *       中提取 features，写回上下文的 key 为路径最后一段</li>
 *   <li>DAG 分支拆分：在 DAG 管线中声明分支起点，使 inputs 依赖与数据流一致</li>
 * </ul>
 *
 * <p>参数：{@code field}（必填，支持点号路径如 {@code payload.features}）
 *
 * <p>YAML 声明：
 * <pre>
 *   - op: extract_field
 *     params: { field: payload.features }
 * </pre>
 */
public class ExtractField implements Step {

    @Override
    public String name() {
        return "extract_field";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = (String) params.get("field");
        if (field == null) {
            throw new BadRequestException("extract_field 缺少 field 参数");
        }

        String[] parts = field.split("\\.");
        Object value = input;
        for (String part : parts) {
            if (value instanceof Map<?, ?> map) {
                value = map.get(part);
            } else {
                throw new BadRequestException("路径 " + field + " 中 " + part + " 不是对象");
            }
        }
        if (value == null) {
            throw new BadRequestException("字段 " + field + " 不存在");
        }

        String targetKey = parts[parts.length - 1];
        return Map.of(targetKey, value);
    }
}
