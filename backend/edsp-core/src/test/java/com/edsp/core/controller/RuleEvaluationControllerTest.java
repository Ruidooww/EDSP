package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.dto.RuleEnabledRequest;
import com.edsp.core.dto.RuleEvaluationRunRequest;
import com.edsp.core.dto.RuleRequest;
import com.edsp.core.service.RuleDecisionRunner;
import com.edsp.core.service.RuleService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

class RuleEvaluationControllerTest {
    @Test
    void ruleAndEvaluationRoutesUseCorePaths() throws Exception {
        var ruleMapping = RuleController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core/rules"}, ruleMapping.value());

        Method listRules = RuleController.class.getMethod("list", int.class);
        assertArrayEquals(new String[] {""}, listRules.getAnnotation(GetMapping.class).value());
        assertEquals("100", listRules.getParameters()[0].getAnnotation(RequestParam.class).defaultValue());

        Method createRule = RuleController.class.getMethod("create", RuleRequest.class);
        assertArrayEquals(new String[] {""}, createRule.getAnnotation(PostMapping.class).value());

        Method enabledRule = RuleController.class.getMethod("setEnabled", long.class, RuleEnabledRequest.class);
        assertArrayEquals(new String[] {"/{id}/enabled"}, enabledRule.getAnnotation(PutMapping.class).value());

        var evaluationMapping = RuleEvaluationController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core/rule-evaluations"}, evaluationMapping.value());

        Method run = RuleEvaluationController.class.getMethod("run", RuleEvaluationRunRequest.class);
        assertArrayEquals(new String[] {"/run"}, run.getAnnotation(PostMapping.class).value());

        Method list = RuleEvaluationController.class.getMethod("list", Long.class, String.class, int.class);
        assertArrayEquals(new String[] {""}, list.getAnnotation(GetMapping.class).value());
        assertEquals("50", list.getParameters()[2].getAnnotation(RequestParam.class).defaultValue());
    }

    @Test
    void controllersDelegateRulesAndEvaluations() {
        var ruleService = new StubRuleService();
        var runner = new StubRuleDecisionRunner();
        var ruleController = new RuleController(ruleService);
        var evaluationController = new RuleEvaluationController(runner);

        var created = ruleController.create(new RuleRequest(
            "High risk",
            "file_operation",
            "high",
            "{\"version\":1,\"mode\":\"structured_config\"}",
            true
        ));
        var listed = ruleController.list(5);
        var enabled = ruleController.setEnabled(7L, new RuleEnabledRequest(false));
        var run = evaluationController.run(new RuleEvaluationRunRequest(3L, 7L, "ops"));
        var decisions = evaluationController.list(3L, "matched", 20);

        assertEquals("High risk", created.data().get("name"));
        assertEquals(5, ruleService.limit);
        assertEquals(7L, enabled.data().get("id"));
        assertEquals(false, enabled.data().get("enabled"));
        assertEquals(3L, run.data().get("standardEventId"));
        assertEquals(7L, run.data().get("ruleId"));
        assertEquals("matched", decisions.data().get(0).get("decision"));
        assertEquals("matched", runner.decision);
    }

    @Test
    void runWithoutStandardEventIdPropagatesBadRequestAndDoesNotReturnDirtyData() {
        var evaluationController = new RuleEvaluationController(new StubRuleDecisionRunner());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class,
            () -> evaluationController.run(new RuleEvaluationRunRequest(null, null, "ops"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    private static class StubRuleService extends RuleService {
        private int limit;

        StubRuleService() {
            super(null, null, null);
        }

        @Override
        public List<Map<String, Object>> list(int limit) {
            this.limit = limit;
            return List.of(Map.of("id", 1L, "name", "Rule"));
        }

        @Override
        public Map<String, Object> create(RuleRequest request) {
            return Map.of("id", 9L, "name", request.name());
        }

        @Override
        public Map<String, Object> setEnabled(long id, RuleEnabledRequest request) {
            return Map.of("id", id, "enabled", request.enabled());
        }
    }

    private static class StubRuleDecisionRunner extends RuleDecisionRunner {
        private String decision;

        StubRuleDecisionRunner() {
            super(null, null, null, null, null);
        }

        @Override
        public Map<String, Object> run(RuleEvaluationRunRequest request) {
            if (request.standardEventId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "standardEventId is required");
            }
            return Map.of(
                "standardEventId", request.standardEventId(),
                "ruleId", request.ruleId(),
                "evaluatedCount", 1
            );
        }

        @Override
        public List<Map<String, Object>> list(Long standardEventId, String decision, int limit) {
            this.decision = decision;
            return List.of(Map.of("standardEventId", standardEventId, "decision", decision));
        }
    }
}
