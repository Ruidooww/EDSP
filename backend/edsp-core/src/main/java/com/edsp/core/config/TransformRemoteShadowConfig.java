package com.edsp.core.config;

import com.edsp.core.transform.HttpTransformRemoteShadowClient;
import com.edsp.core.transform.TransformRemoteShadowClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransformRemoteShadowConfig {
    @Bean
    public TransformRemoteShadowClient transformRemoteShadowClient(
        ObjectMapper objectMapper,
        @Value("${edsp.transform.remote-shadow-enabled:false}") boolean enabled,
        @Value("${edsp.transform.remote-base-url:http://edsp-transform-service:8080}") String baseUrl,
        @Value("${edsp.transform.remote-timeout-ms:1000}") int timeoutMs
    ) {
        if (!enabled) {
            return TransformRemoteShadowClient.disabled();
        }
        return new HttpTransformRemoteShadowClient(objectMapper, baseUrl, timeoutMs);
    }
}
