package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.RuleEnabledRequest;
import com.edsp.core.dto.RuleRequest;
import com.edsp.core.service.RuleService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/rules")
public class RuleController {
    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping("")
    public ApiResponse<List<Map<String, Object>>> list(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(ruleService.list(limit));
    }

    @PostMapping("")
    public ApiResponse<Map<String, Object>> create(@RequestBody RuleRequest request) {
        return ApiResponse.ok(ruleService.create(request), "created");
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Map<String, Object>> setEnabled(
        @PathVariable("id") long id,
        @RequestBody RuleEnabledRequest request
    ) {
        return ApiResponse.ok(ruleService.setEnabled(id, request), "updated");
    }
}
