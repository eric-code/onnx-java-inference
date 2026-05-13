package com.kudosol.ai.inference.exception;

public class RequestTimeoutException extends BusinessException {

    public RequestTimeoutException(String message) {
        super(503, message);
    }
}
