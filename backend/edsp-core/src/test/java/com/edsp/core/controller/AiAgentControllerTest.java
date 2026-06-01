package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

class AiAgentControllerTest {
    @Test
    void recentRunsRouteUsesExplicitLimitRequestParameterBinding() throws Exception {
        Method recent = AiAgentController.class.getMethod("recent", int.class);

        assertArrayEquals(new String[] {"/runs/recent"}, recent.getAnnotation(GetMapping.class).value());
        assertEquals("limit", recent.getParameters()[0].getAnnotation(RequestParam.class).name());
        assertEquals("10", recent.getParameters()[0].getAnnotation(RequestParam.class).defaultValue());
    }
}
