package com.edsp.core.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "edsp.ingestion-plan.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class IngestionPlanSyncScheduler {
    private final IngestionPlanSyncScheduleService scheduleService;
    private final String lockOwner = "scheduler-" + UUID.randomUUID();

    public IngestionPlanSyncScheduler(IngestionPlanSyncScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Scheduled(fixedDelayString = "${edsp.ingestion-plan.scheduler.poll-ms:30000}")
    public void runDueSchedules() {
        scheduleService.runDueSchedules(lockOwner, 20);
    }
}
