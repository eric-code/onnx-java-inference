package com.kudosol.ai.inference.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(int code, T data, String error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, data, null);
    }

    public static <T> ApiResponse<T> fail(int code, String error) {
        return new ApiResponse<>(code, null, error);
    }
}
