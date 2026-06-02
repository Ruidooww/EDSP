package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

class AiAgentRunHistoryControllerTest {
    @Test
    void historyControllerUsesStableSafeReadRoutes() throws Exception {
        assertArrayEquals(
            new String[] {"/api/core/ai-agents/runs"},
            AiAgentRunHistoryController.class.getAnnotation(RequestMapping.class).value()
        );

        Method list = AiAgentRunHistoryController.class.getMethod(
            "list", int.class, String.class, String.class, String.class, String.class, String.class
        );
        Method detail = AiAgentRunHistoryController.class.getMethod("detail", long.class);

        assertArrayEquals(new String[] {}, list.getAnnotation(GetMapping.class).value());
        assertEquals("limit", list.getParameters()[0].getAnnotation(RequestParam.class).name());
        assertArrayEquals(new String[] {"/{id}"}, detail.getAnnotation(GetMapping.class).value());
        assertEquals("id", detail.getParameters()[0].getAnnotation(PathVariable.class).value());
    }
}
