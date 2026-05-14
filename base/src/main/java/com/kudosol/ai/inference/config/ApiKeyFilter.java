package com.kudosol.ai.inference.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final Set<String> PUBLIC_PATHS = Set.of("/actuator");
    private static final byte[] UNAUTHORIZED_BODY = """
            {"code":401,"data":null,"error":"无效或缺失 API Key"}"""
            .getBytes(StandardCharsets.UTF_8);

    private final Set<String> validKeys;

    public ApiKeyFilter(InferenceProperties properties) {
        this.validKeys = properties.getApiKeys().stream()
                .filter(k -> !k.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (validKeys.isEmpty()) return true;
        String path = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key != null && validKeys.contains(key)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(UNAUTHORIZED_BODY);
    }
}
