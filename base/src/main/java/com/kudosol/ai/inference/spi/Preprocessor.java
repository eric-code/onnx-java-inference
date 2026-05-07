package com.kudosol.ai.inference.spi;

import ai.onnxruntime.OnnxTensor;
import java.util.Map;

/**
 * 模型前处理接口，每个模型目录下的 preprocessor.jar 需实现此接口。
 */
public interface Preprocessor {

    /**
     * 将原始请求数据转为 ONNX 模型输入张量。
     *
     * @param inputData 原始请求体字节
     * @param params    请求参数
     * @return 模型输入名到张量的映射
     */
    Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) throws Exception;
}
