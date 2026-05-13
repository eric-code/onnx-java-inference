package com.kudosol.ai.inference.step;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.TensorMeta;

import java.util.List;
import java.util.Map;

public final class StepContextSupport {

    public static final String META_KEY = "_meta";

    private StepContextSupport() {
    }

    public static ModelMeta meta(Map<String, Object> ctx) {
        Object meta = ctx.get(META_KEY);
        if (!(meta instanceof ModelMeta m)) {
            throw new IllegalStateException("管线未注入 ModelMeta");
        }
        return m;
    }

    public static String resolveInputField(Map<String, Object> ctx, Map<String, Object> params, String stepName) {
        String explicit = readField(params);
        if (explicit != null) return explicit;
        ModelMeta m = meta(ctx);
        List<TensorMeta> inputs = m.getInputs();
        if (inputs.size() == 1) return inputs.get(0).getName();
        throw new BadRequestException("步骤 " + stepName + " 未指定 field，且模型有 "
                + inputs.size() + " 个输入 " + names(inputs) + "，请显式声明 field 参数");
    }

    public static String resolveOutputField(Map<String, Object> ctx, Map<String, Object> params, String stepName) {
        String explicit = readField(params);
        if (explicit != null) return explicit;
        ModelMeta m = meta(ctx);
        List<TensorMeta> outputs = m.getOutputs();
        if (outputs.size() == 1) return outputs.get(0).getName();
        throw new BadRequestException("步骤 " + stepName + " 未指定 field，且模型有 "
                + outputs.size() + " 个输出 " + names(outputs) + "，请显式声明 field 参数");
    }

    public static TensorMeta findInput(ModelMeta meta, String name) {
        for (TensorMeta tm : meta.getInputs()) {
            if (tm.getName().equals(name)) return tm;
        }
        throw new BadRequestException("to_tensor field='" + name
                + "' 在 ONNX 输入中不存在，已知输入: " + names(meta.getInputs()));
    }

    private static String readField(Map<String, Object> params) {
        Object field = params == null ? null : params.get("field");
        return (field instanceof String f && !f.isEmpty()) ? f : null;
    }

    private static List<String> names(List<TensorMeta> list) {
        return list.stream().map(TensorMeta::getName).toList();
    }
}
