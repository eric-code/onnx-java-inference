package com.kudosol.ai.inference.host;

import com.kudosol.ai.inference.config.HostProcProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HostNetworkService {

    private static final int TCP_LISTEN = 0x0A;

    private final HostProcProperties props;

    private static int parseHex(String hex) {
        return (int) Long.parseLong(hex, 16);
    }

    private static String parseIp(String hex, boolean isV6) {
        if (!isV6) {
            // IPv4: hex is in little-endian byte order, e.g. 0100007F -> 127.0.0.1
            long val = Long.parseLong(hex, 16);
            return ((val & 0xFF) + "." +
                    ((val >> 8) & 0xFF) + "." +
                    ((val >> 16) & 0xFF) + "." +
                    ((val >> 24) & 0xFF));
        }
        // IPv6: 32 hex chars, 4 groups of 32-bit little-endian words
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i += 8) {
            String word = hex.substring(i, i + 8);
            long val = Long.parseLong(word, 16);
            // Each 32-bit word is little-endian
            int g0 = (int) (val & 0xFFFF);
            int g1 = (int) ((val >> 16) & 0xFFFF);
            if (i > 0) sb.append(":");
            sb.append(String.format("%x:%x", g1, g0));
        }
        return sb.toString();
    }

    public List<ListeningPort> getListeningPorts() {
        List<ListeningPort> result = new ArrayList<>();
        parseNetFile("tcp", "TCP", result);
        parseNetFile("tcp6", "TCP6", result);
        parseNetFile("udp", "UDP", result);
        parseNetFile("udp6", "UDP6", result);
        return result;
    }

    private void parseNetFile(String filename, String protocol, List<ListeningPort> result) {
        Path path = Path.of(props.getBasePath(), "1", "net", filename);
        if (!Files.exists(path)) {
            // fallback to /proc/net if /proc/1/net not available
            path = Path.of(props.getBasePath(), "net", filename);
            if (!Files.exists(path)) {
                log.warn("Host proc net file not found: {}", path);
                return;
            }
        }
        try {
            List<String> lines = Files.readAllLines(path);
            // Skip header line
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 10) continue;

                int state = parseHex(parts[3]);
                boolean isUdp = filename.startsWith("udp");
                // For TCP: state 0A = LISTEN
                // For UDP: state 07 = unconnected (listening without a peer)
                if ((isUdp && state == 0x07) || (!isUdp && state == TCP_LISTEN)) {
                    String[] addrParts = parts[1].split(":");
                    if (addrParts.length != 2) continue;

                    String ip = parseIp(addrParts[0], filename.endsWith("6"));
                    int port = parseHex(addrParts[1]);

                    result.add(new ListeningPort(protocol, ip, port));
                }
            }
        } catch (IOException e) {
            log.error("Failed to read host proc net file: {}", path, e);
        }
    }
}
