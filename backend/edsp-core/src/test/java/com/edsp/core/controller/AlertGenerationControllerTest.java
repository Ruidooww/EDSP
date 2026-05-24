package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.service.AlertGenerationService;
import com.edsp.core.service.AlertRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

class AlertGenerationControllerTest {
    @Test
    void alertGenerationAndCoreAlertRoutesUseCorePaths() throws Exception {
        var generationMapping = AlertGenerationController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core/alert-generations"}, generationMapping.value());

        Method run = AlertGenerationController.class.getMethod("run", Map.class);
        assertArrayEquals(new String[] {"/run"}, run.getAnnotation(PostMapping.class).value());

        var alertsMapping = CoreAlertController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core/alerts"}, alertsMapping.value());

        Method list = CoreAlertController.class.getMethod("list", String.class, String.class, int.class);
        assertArrayEquals(new String[] {""}, list.getAnnotation(GetMapping.class).value());
        assertEquals("50", list.getParameters()[2].getAnnotation(RequestParam.class).defaultValue());
    }

    @Test
    void controllersDelegateGenerationAndAlertList() {
        var generationService = new StubAlertGenerationService();
        var repository = new StubAlertRepository();
        var generationController = new AlertGenerationController(generationService);
        var alertController = new CoreAlertController(repository);

        var generated = generationController.run(Map.of("decisionId", 12L));
        var alerts = alertController.list("open", "high", 300);

        assertEquals(12L, generated.data().get("decisionId"));
        assertEquals("open", repository.status);
        assertEquals("high", repository.severity);
        assertEquals(300, repository.limit);
        assertEquals("Alert", alerts.data().get(0).get("title"));
    }

    @Test
    void runWithoutDecisionIdPropagatesBadRequest() {
        var controller = new AlertGenerationController(new StubAlertGenerationService());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class,
            () -> controller.run(Map.of())
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void runRejectsAlternateCreationInputs() {
        var controller = new AlertGenerationController(new StubAlertGenerationService());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class,
            () -> controller.run(Map.of("decisionId", 12L, "ruleId", 34L))
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    private static class StubAlertGenerationService extends AlertGenerationService {
        StubAlertGenerationService() {
            super(null, null, null, null);
        }

        @Override
        public Map<String, Object> generate(com.edsp.core.dto.AlertGenerationRunRequest request) {
            if (request.decisionId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decisionId is required");
            }
            return Map.of("decisionId", request.decisionId(), "action", "created");
        }
    }

    private static class StubAlertRepository extends AlertRepository {
        private String status;
        private String severity;
        private int limit;

        StubAlertRepository() {
            super(null, null, null);
        }

        @Override
        public List<Map<String, Object>> list(String status, String severity, int limit) {
            this.status = status;
            this.severity = severity;
            this.limit = limit;
            return List.of(Map.of("id", 1L, "title", "Alert"));
        }
    }
}
