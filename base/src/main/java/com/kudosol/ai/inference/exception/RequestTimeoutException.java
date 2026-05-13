package com.kudosol.ai.inference.exception;

import java.io.Serial;

public class RequestTimeoutException extends BusinessException {
    @Serial
    private static final long serialVersionUID = -8275840447391335390L;

    public RequestTimeoutException(String message) {
        super(503, message);
    }
}
