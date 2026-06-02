package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class AiAgentProviderConfigControllerTest {
    @Test
    void providerConfigControllerUsesStableReadinessRoutes() throws Exception {
        assertArrayEquals(
            new String[] {"/api/core/ai-agent-provider-configs"},
            AiAgentProviderConfigController.class.getAnnotation(RequestMapping.class).value()
        );

        Method list = AiAgentProviderConfigController.class.getMethod("list");
        Method test = AiAgentProviderConfigController.class.getMethod("test", String.class);

        assertArrayEquals(new String[] {}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{providerKey}/test"}, test.getAnnotation(PostMapping.class).value());
        assertEquals("providerKey", test.getParameters()[0].getAnnotation(PathVariable.class).value());
    }
}
