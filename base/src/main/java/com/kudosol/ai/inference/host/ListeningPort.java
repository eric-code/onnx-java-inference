package com.kudosol.ai.inference.host;

public record ListeningPort(
        String protocol,
        String localAddress,
        int port
) {
}
