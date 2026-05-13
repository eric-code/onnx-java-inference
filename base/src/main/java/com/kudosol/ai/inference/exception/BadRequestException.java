package com.kudosol.ai.inference.exception;

import java.io.Serial;

public class BadRequestException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 3642431063421247665L;

    public BadRequestException(String message) {
        super(400, message);
    }
}
