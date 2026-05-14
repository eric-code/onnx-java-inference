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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_ATTR = "X-API-Key";
    private static final String HEADER = "X-API-Key";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key != null) {
            request.setAttribute(API_KEY_ATTR, key);
        }
        filterChain.doFilter(request, response);
    }
}
