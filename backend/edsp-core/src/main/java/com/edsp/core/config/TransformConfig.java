package com.edsp.core.config;

import com.edsp.transform.standardevent.StandardEventTransformService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransformConfig {
    @Bean
    public StandardEventTransformService standardEventTransformService() {
        return new StandardEventTransformService();
    }
}
