package com.kudosol.ai.inference.exception;

import java.io.Serial;

public class NotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = -9066026887361876917L;

    public NotFoundException(String message) {
        super(404, message);
    }
}
