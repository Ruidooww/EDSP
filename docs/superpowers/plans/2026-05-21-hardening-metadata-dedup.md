# Metadata Scan And Event Dedup Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix metadata scan coverage accounting, prevent limited scans from marking missing objects removed, unify standard event deduplication, and harden data source ID handling.

**Architecture:** Keep the current Spring JDBC/Flyway structure. Extend `JdbcMetadataScanService.MetadataScanResult` to carry scan coverage, persist it in `schema_scan_runs`, and gate removal detection on full coverage. Add a small shared `StandardEventDedupService` so `IngestionService` and `CollectionTaskService` use the same lookup order.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring JDBC, Flyway, H2 test database, JUnit 5.

---

### Task 1: Metadata scan coverage

**Files:**
- Modify: `backend/edsp-core/src/main/java/com/edsp/core/service/JdbcMetadataScanService.java`
- Modify: `backend/edsp-core/src/main/java/com/edsp/core/service/SchemaScanService.java`
- Modify: `backend/edsp-core/src/main/java/com/edsp/core/dto/SchemaScanFinishRequest.java`
- Create: `backend/edsp-core/src/main/resources/db/migration/V7__schema_scan_coverage.sql`
- Test: `backend/edsp-core/src/test/java/com/edsp/core/service/SchemaScanServiceTest.java`

- [x] **Step 1: Write failing tests**

Create tests proving limited scans do not mark old active objects as removed, full scans still do, and run rows record `total_tables`, `scanned_tables`, `limited`, and `coverage_rate`.

- [x] **Step 2: Run tests to verify failure**

Run: `mvn -pl edsp-core -Dtest=SchemaScanServiceTest test`
Expected: FAIL before implementation.

- [x] **Step 3: Implement coverage metadata**

Add `limited` and `coverage_rate` columns. Count available tables before applying limits. Record `totalAvailableTables` in `total_tables`, scanned tables in `scanned_tables`, and skip `markMissingObjects()` unless `!result.limited()`.

- [x] **Step 4: Verify**

Run: `mvn -pl edsp-core -Dtest=SchemaScanServiceTest test`
Expected: PASS.

### Task 2: Standard event dedup and data source hardening

**Files:**
- Create: `backend/edsp-core/src/main/java/com/edsp/core/service/StandardEventDedupService.java`
- Modify: `backend/edsp-core/src/main/java/com/edsp/core/service/IngestionService.java`
- Modify: `backend/edsp-core/src/main/java/com/edsp/core/service/CollectionTaskService.java`
- Modify: `backend/edsp-core/src/main/java/com/edsp/core/controller/DataSourceController.java`
- Test: `backend/edsp-core/src/test/java/com/edsp/core/service/CollectionTaskServiceTest.java`
- Test: `backend/edsp-core/src/test/java/com/edsp/core/controller/DataSourceControllerTest.java`

- [x] **Step 1: Write failing tests**

Add tests for repeated collection using the same computed `dedup_key`, missing data source IDs returning `HttpStatus.NOT_FOUND`, and data source creation returning a positive generated ID.

- [x] **Step 2: Run tests to verify failure**

Run: `mvn -pl edsp-core -Dtest=CollectionTaskServiceTest,DataSourceControllerTest test`
Expected: FAIL before implementation.

- [x] **Step 3: Implement shared lookup and 404/id handling**

Move standard event lookup to `StandardEventDedupService.findExistingStandardEventId(...)`. Use it from both ingestion paths. Replace data source `queryForMap` with `queryForList` plus `ResponseStatusException(HttpStatus.NOT_FOUND, "Data source not found: " + id)`. Throw if generated ID cannot be read.

- [x] **Step 4: Verify**

Run: `mvn -pl edsp-core -Dtest=CollectionTaskServiceTest,DataSourceControllerTest test`
Expected: PASS.

### Task 3: Full backend verification

**Files:**
- No additional files.

- [x] **Step 1: Run full affected module tests**

Run: `mvn -pl edsp-core -am test`
Expected: PASS.

- [x] **Step 2: Inspect git diff**

Run: `git status --short` and `git diff --stat`
Expected: only hardening files and this plan changed; unrelated `agent.md` remains untracked and unstaged.
