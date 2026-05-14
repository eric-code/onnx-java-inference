package com.kudosol.ai.inference.exception;

import java.io.Serial;

public class UnauthorizedException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 7382910468234567890L;

    public UnauthorizedException(String message) {
        super(401, message);
    }
}
