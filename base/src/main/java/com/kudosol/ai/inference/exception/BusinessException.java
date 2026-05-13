package com.kudosol.ai.inference.exception;

import lombok.Getter;

import java.io.Serial;

@Getter
public class BusinessException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -7550845656075871071L;
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
