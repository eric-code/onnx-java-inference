package com.kudosol.ai.inference.exception;

import java.io.Serial;

public class TooManyRequestException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 9162458018746576227L;

    public TooManyRequestException(String message) {
        super(429, message);
    }
}
