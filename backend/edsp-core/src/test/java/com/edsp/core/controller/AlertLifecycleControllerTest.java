package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.dto.AlertLifecycleRequest;
import com.edsp.core.service.AlertLifecycleService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

class AlertLifecycleControllerTest {
    @Test
    void alertLifecycleRoutesUseCorePaths() throws Exception {
        var controllerMapping = AlertLifecycleController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core/alerts"}, controllerMapping.value());

        Method acknowledge = AlertLifecycleController.class.getMethod("acknowledge", long.class, AlertLifecycleRequest.class);
        Method assign = AlertLifecycleController.class.getMethod("assign", long.class, AlertLifecycleRequest.class);
        Method close = AlertLifecycleController.class.getMethod("close", long.class, AlertLifecycleRequest.class);
        Method timeline = AlertLifecycleController.class.getMethod("timeline", long.class);

        assertArrayEquals(new String[] {"/{id}/acknowledge"}, acknowledge.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/assign"}, assign.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/close"}, close.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{id}/timeline"}, timeline.getAnnotation(GetMapping.class).value());
        assertEquals("id", acknowledge.getParameters()[0].getAnnotation(PathVariable.class).value());
    }

    @Test
    void controllerDelegatesLifecycleOperations() {
        var service = new StubAlertLifecycleService();
        var controller = new AlertLifecycleController(service);

        var acknowledge = controller.acknowledge(11L, new AlertLifecycleRequest("ops", null, "确认"));
        var assign = controller.assign(12L, new AlertLifecycleRequest("ops", "zhangsan", "转派"));
        var close = controller.close(13L, new AlertLifecycleRequest("ops", null, "关闭"));
        var timeline = controller.timeline(14L);

        assertEquals("acknowledge", acknowledge.data().get("action"));
        assertEquals("assign", assign.data().get("action"));
        assertEquals("close", close.data().get("action"));
        assertEquals(14L, timeline.data().get(0).get("alertId"));
    }

    @Test
    void closeWithoutNotePropagatesBadRequest() {
        var controller = new AlertLifecycleController(new StubAlertLifecycleService());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class,
            () -> controller.close(13L, new AlertLifecycleRequest("ops", null, " "))
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    private static class StubAlertLifecycleService extends AlertLifecycleService {
        StubAlertLifecycleService() {
            super(null, null);
        }

        @Override
        public Map<String, Object> acknowledge(long alertId, AlertLifecycleRequest request) {
            return Map.of("id", alertId, "action", "acknowledge");
        }

        @Override
        public Map<String, Object> assign(long alertId, AlertLifecycleRequest request) {
            return Map.of("id", alertId, "action", "assign");
        }

        @Override
        public Map<String, Object> close(long alertId, AlertLifecycleRequest request) {
            if (request == null || request.note() == null || request.note().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "close_note_required");
            }
            return Map.of("id", alertId, "action", "close");
        }

        @Override
        public List<Map<String, Object>> timeline(long alertId) {
            return List.of(Map.of("alertId", alertId, "eventType", "assigned"));
        }
    }
}
