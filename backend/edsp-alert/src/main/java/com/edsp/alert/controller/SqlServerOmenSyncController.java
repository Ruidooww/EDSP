package com.edsp.alert.controller;

import com.edsp.alert.service.SqlServerOmenSyncService;
import com.edsp.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest/sqlserver/omen")
public class SqlServerOmenSyncController {
    private final SqlServerOmenSyncService syncService;

    public SqlServerOmenSyncController(SqlServerOmenSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/sync")
    public ApiResponse<Map<String, Object>> sync(
        @RequestParam(name = "dataSourceId", defaultValue = "1") long dataSourceId,
        @RequestParam(name = "database", defaultValue = "OCULAR3_REPORT2") String database,
        @RequestParam(name = "tableLimit", defaultValue = "50") int tableLimit,
        @RequestParam(name = "rowLimit", defaultValue = "100") int rowLimit
    ) {
        return ApiResponse.ok(syncService.sync(dataSourceId, database, tableLimit, rowLimit));
    }

    @GetMapping("/restored")
    public ApiResponse<Map<String, Object>> restored(
        @RequestParam(name = "dataSourceId", defaultValue = "1") long dataSourceId,
        @RequestParam(name = "database", defaultValue = "OCULAR3_REPORT2") String database,
        @RequestParam(name = "mainDatabase", defaultValue = "OCULAR3") String mainDatabase,
        @RequestParam(name = "tableLimit", defaultValue = "50") int tableLimit,
        @RequestParam(name = "rowLimit", defaultValue = "100") int rowLimit
    ) {
        return ApiResponse.ok(syncService.restored(dataSourceId, database, mainDatabase, tableLimit, rowLimit));
    }
}
