package com.kudosol.ai.inference.exception;

import java.io.Serial;

public class ForbiddenException extends BusinessException {
    @Serial
    private static final long serialVersionUID = -4578392012345678901L;

    public ForbiddenException(String message) {
        super(403, message);
    }
}
