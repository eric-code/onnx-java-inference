package com.kudosol.ai.inference.exception;

import java.io.Serial;

public class PayloadTooLargeException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 4204920221663954915L;

    public PayloadTooLargeException(String message) {
        super(413, message);
    }
}
