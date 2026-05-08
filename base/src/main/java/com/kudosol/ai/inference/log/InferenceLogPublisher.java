package com.kudosol.ai.inference.log;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InferenceLogPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publish(InferenceLog inferenceLog) {
        log.info("[{}] {} {} - {} ({}ms)",
                inferenceLog.level(), inferenceLog.model(), inferenceLog.phase(),
                inferenceLog.message(),
                inferenceLog.durationMs() != null ? inferenceLog.durationMs() : "-");

        messagingTemplate.convertAndSend("/topic/logs", inferenceLog);
        messagingTemplate.convertAndSend("/topic/logs/" + inferenceLog.model(), inferenceLog);

        if ("ERROR".equals(inferenceLog.level())) {
            messagingTemplate.convertAndSend("/topic/errors", inferenceLog);
        }
    }
}
