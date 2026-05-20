package com.edsp.core.dto;

import jakarta.validation.constraints.NotBlank;

public record CollectionTaskRequest(
    long dataSourceId,
    Long adapterId,
    @NotBlank String name,
    String taskType,
    String scheduleMode,
    Integer intervalSeconds,
    String status,
    boolean enabled,
    String configJson
) {
    public CollectionTaskRequest {
        if (taskType == null || taskType.isBlank()) {
            taskType = "pull";
        }
        if (scheduleMode == null || scheduleMode.isBlank()) {
            scheduleMode = "manual";
        }
        if (intervalSeconds == null || intervalSeconds < 1) {
            intervalSeconds = 300;
        }
        if (status == null || status.isBlank()) {
            status = "draft";
        }
        if (configJson == null || configJson.isBlank()) {
            configJson = "{}";
        }
    }
}
