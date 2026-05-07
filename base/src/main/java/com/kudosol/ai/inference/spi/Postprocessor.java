package com.kudosol.ai.inference.spi;

import ai.onnxruntime.OnnxTensor;
import java.util.Map;

/**
 * 模型后处理接口，每个模型目录下的 postprocessor.jar 需实现此接口。
 */
public interface Postprocessor {

    /**
     * 初始化，传入模型元数据（输入输出的名称、类型、shape）。
     * 在 process() 之前调用。
     */
    default void init(ModelMeta meta) {
    }

    /**
     * 将 ONNX 模型输出张量转为可序列化的结果。
     *
     * @param output 模型输出名到张量的映射
     * @return 可 JSON 序列化的结果
     */
    Map<String, Object> process(Map<String, OnnxTensor> output) throws Exception;
}
