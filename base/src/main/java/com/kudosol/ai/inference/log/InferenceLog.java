package com.kudosol.ai.inference.log;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InferenceLog(
        Instant timestamp,
        String model,
        String phase,
        String level,
        String message,
        Long durationMs,
        String error
) {
    public static InferenceLog info(String model, String phase, String message, Long durationMs) {
        return new InferenceLog(Instant.now(), model, phase, "INFO", message, durationMs, null);
    }

    public static InferenceLog error(String model, String phase, String message, String error) {
        return new InferenceLog(Instant.now(), model, phase, "ERROR", message, null, error);
    }
}
