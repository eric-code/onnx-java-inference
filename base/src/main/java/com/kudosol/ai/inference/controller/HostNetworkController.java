package com.kudosol.ai.inference.controller;

import com.kudosol.ai.inference.host.HostNetworkService;
import com.kudosol.ai.inference.host.ListeningPort;
import com.kudosol.ai.inference.protocol.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/host")
@RequiredArgsConstructor
public class HostNetworkController {

    private final HostNetworkService hostNetworkService;

    @GetMapping("/ports")
    public ApiResponse<Map<String, Object>> getListeningPorts() {
        List<ListeningPort> ports = hostNetworkService.getListeningPorts();
        return ApiResponse.ok(Map.of("count", ports.size(), "ports", ports));
    }
}
