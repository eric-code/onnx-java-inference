package com.kudosol.ai.inference.exception;

public class TooManyRequestException extends BusinessException {

    public TooManyRequestException(String message) {
        super(429, message);
    }
}
