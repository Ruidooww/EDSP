package com.edsp.core.config;

import com.edsp.core.transform.runtime.FallbackTransformRuntimeClient;
import com.edsp.core.transform.runtime.LocalTransformRuntimeClient;
import com.edsp.core.transform.runtime.RemoteTransformRuntimeClient;
import com.edsp.core.transform.runtime.TransformRuntimeClient;
import com.edsp.transform.standardevent.StandardEventTransformService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransformRuntimeConfig {
    @Bean
    public TransformRuntimeClient transformRuntimeClient(
        StandardEventTransformService transformService,
        ObjectMapper objectMapper,
        @Value("${edsp.transform.runtime-mode:local}") String runtimeMode,
        @Value("${edsp.transform.remote-base-url:http://edsp-transform-service:8085}") String baseUrl,
        @Value("${edsp.transform.remote-timeout-ms:1000}") int timeoutMs
    ) {
        var mode = runtimeMode == null ? "local" : runtimeMode.trim().toLowerCase(Locale.ROOT);
        var local = new LocalTransformRuntimeClient(transformService);
        return switch (mode) {
            case "local" -> local;
            case "remote" -> new RemoteTransformRuntimeClient(objectMapper, baseUrl, timeoutMs);
            case "fallback" -> new FallbackTransformRuntimeClient(
                new RemoteTransformRuntimeClient(objectMapper, baseUrl, timeoutMs),
                local
            );
            default -> throw new IllegalStateException("invalid_transform_runtime_mode: " + runtimeMode);
        };
    }
}
