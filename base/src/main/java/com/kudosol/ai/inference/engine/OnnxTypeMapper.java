package com.kudosol.ai.inference.engine;

import ai.onnxruntime.OnnxJavaType;

public final class OnnxTypeMapper {

    private OnnxTypeMapper() {
    }

    public static String toYmlType(OnnxJavaType type) {
        return switch (type) {
            case FLOAT -> "float32";
            case DOUBLE -> "float64";
            case INT64 -> "int64";
            case INT32 -> "int32";
            case INT16 -> "int16";
            case INT8 -> "int8";
            case UINT8 -> "uint8";
            case BOOL -> "bool";
            case STRING -> "string";
            default -> throw new IllegalArgumentException("暂不支持的 ONNX 类型: " + type);
        };
    }
}
