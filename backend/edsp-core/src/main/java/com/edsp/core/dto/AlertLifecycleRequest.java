package com.edsp.core.dto;

public record AlertLifecycleRequest(
    String operatorName,
    String assignee,
    String note
) {}
