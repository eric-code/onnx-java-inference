package com.kudosol.ai.inference.exception;

public class PayloadTooLargeException extends BusinessException {

    public PayloadTooLargeException(String message) {
        super(413, message);
    }
}
