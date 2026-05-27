package com.edsp.transformservice;

import com.edsp.transform.standardevent.StandardEventTransformService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransformServiceConfig {
    @Bean
    public StandardEventTransformService standardEventTransformService() {
        return new StandardEventTransformService();
    }
}
