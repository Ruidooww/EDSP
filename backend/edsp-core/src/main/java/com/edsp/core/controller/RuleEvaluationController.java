package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.RuleEvaluationRunRequest;
import com.edsp.core.service.RuleDecisionRunner;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/rule-evaluations")
public class RuleEvaluationController {
    private final RuleDecisionRunner runner;

    public RuleEvaluationController(RuleDecisionRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody RuleEvaluationRunRequest request) {
        return ApiResponse.ok(runner.run(request), "evaluated");
    }

    @GetMapping("")
    public ApiResponse<List<Map<String, Object>>> list(
        @RequestParam(name = "standardEventId", required = false) Long standardEventId,
        @RequestParam(name = "decision", required = false) String decision,
        @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(runner.list(standardEventId, decision, limit));
    }
}
