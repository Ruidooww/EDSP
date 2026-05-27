[CmdletBinding()]
param(
    [string]$ComposeProject = "edsp_smoke",
    [string]$SmokeDatabase = "edsp_transform_runtime_smoke",
    [string]$SmokeSchema = "transform_runtime_smoke",
    [int]$FrontendPort = 18080,
    [int]$TransformPort = 18085,
    [switch]$CiMode,
    [switch]$CollectLogsOnFailure,
    [ValidateSet("Keep", "Stop")]
    [string]$FinalAction = "Keep",
    [string]$ArtifactRoot = "logs/transform-runtime-smoke",
    [int]$ReadyAttempts = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runId = "$(Get-Date -Format 'yyyyMMddHHmmss')_$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
if ($CiMode -and -not $PSBoundParameters.ContainsKey("ComposeProject")) {
    $ComposeProject = "edsp_smoke_ci_$runId"
}
if ($CiMode -and -not $PSBoundParameters.ContainsKey("FinalAction")) {
    $FinalAction = "Stop"
}
$collectLogs = $CollectLogsOnFailure.IsPresent -or $CiMode.IsPresent
$artifactRootPath = if ([System.IO.Path]::IsPathRooted($ArtifactRoot)) {
    $ArtifactRoot
} else {
    Join-Path $repoRoot $ArtifactRoot
}
$artifactPath = Join-Path $artifactRootPath $runId
$runtimeTouched = $false
$transformStopped = $false
$failure = $null
$failureStage = $null
$failureType = $null
$warnings = New-Object System.Collections.Generic.List[string]
$scenarioResults = [ordered]@{
    remoteSuccess = "NOT_RUN"
    remoteUnavailable = "NOT_RUN"
    fallbackUnavailable = "NOT_RUN"
    transformRuntimeVerification = "NOT_RUN"
}

function Require-SafeIdentifier {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($Value -notmatch '^[a-zA-Z][a-zA-Z0-9_]*$') {
        throw "$Label must contain only letters, numbers, and underscores and start with a letter."
    }
}

function Quote-Identifier {
    param([Parameter(Mandatory = $true)][string]$Value)
    Require-SafeIdentifier -Value $Value -Label "SQL identifier"
    return '"' + $Value + '"'
}

function Quote-SqlText {
    param([AllowEmptyString()][string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

function Invoke-DockerCapture {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "docker $($Arguments -join ' ') failed.`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-DockerVisible {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = @(Invoke-DockerCapture -Arguments $Arguments)
    if ($output.Count -gt 0) {
        $output | ForEach-Object { Write-Host $_ }
    }
}

function Add-SmokeWarning {
    param([Parameter(Mandatory = $true)][string]$Message)
    $warnings.Add($Message)
    Write-Warning $Message
}

function Assert-ArtifactRootIgnored {
    if (-not ($CiMode -or $collectLogs)) {
        return
    }
    if ([System.IO.Path]::IsPathRooted($ArtifactRoot)) {
        $absoluteArtifactRoot = [System.IO.Path]::GetFullPath($ArtifactRoot)
        $relativeToRepo = [System.IO.Path]::GetRelativePath($repoRoot, $absoluteArtifactRoot).Replace('\', '/')
        if ($relativeToRepo -eq ".." -or $relativeToRepo.StartsWith("../")) {
            return
        }
    }

    $relativeArtifactRoot = if ([System.IO.Path]::IsPathRooted($ArtifactRoot)) {
        [System.IO.Path]::GetRelativePath($repoRoot, $ArtifactRoot)
    } else {
        $ArtifactRoot
    }
    $relativeArtifactRoot = $relativeArtifactRoot.Replace('\', '/')
    if ($relativeArtifactRoot -eq "logs" -or $relativeArtifactRoot.StartsWith("logs/")) {
        return
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git -C $repoRoot check-ignore -q -- $relativeArtifactRoot
        $ignored = $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if (-not $ignored) {
        throw "ArtifactRoot '$ArtifactRoot' is not ignored by git. Use an ignored path such as logs/transform-runtime-smoke."
    }
}

function Save-CommandOutput {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    try {
        New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
        $output = @(Invoke-DockerCapture -Arguments $Arguments)
        $output | Set-Content -Path (Join-Path $artifactPath $FileName) -Encoding UTF8
    } catch {
        Add-SmokeWarning "Unable to collect $FileName`: $($_.Exception.Message)"
    }
}

function Collect-SmokeArtifacts {
    if (-not $collectLogs) {
        return
    }

    Save-CommandOutput -FileName "ps.txt" -Arguments @("compose", "-p", $ComposeProject, "ps", "-a")
    foreach ($service in @("postgres", "edsp-core", "edsp-transform-service", "edsp-gateway", "frontend")) {
        Save-CommandOutput -FileName "logs-$service.txt" -Arguments @(
            "compose", "-p", $ComposeProject, "logs", "--tail=300", $service
        )
    }
}

function Write-SmokeSummary {
    if (-not ($CiMode -or $collectLogs)) {
        return
    }

    try {
        New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
        $summary = [ordered]@{
            runId = $runId
            composeProject = $ComposeProject
            frontendPort = $FrontendPort
            transformPort = $TransformPort
            ciMode = [bool]$CiMode
            finalAction = $FinalAction
            artifactPath = $artifactPath
            scenarios = $scenarioResults
            failureStage = $failureStage
            failureType = $failureType
            failureMessage = if ($null -eq $failure) { $null } else { $failure.Exception.Message }
            warnings = @($warnings)
        }
        $summary |
            ConvertTo-Json -Depth 6 |
            Set-Content -Path (Join-Path $artifactPath "summary.json") -Encoding UTF8
        Write-Host "Smoke summary artifact: $(Join-Path $artifactPath "summary.json")"
    } catch {
        Write-Warning "Unable to write smoke summary artifact: $($_.Exception.Message)"
    }
}

function Invoke-ComposeVisible {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    Invoke-DockerVisible -Arguments (@("compose", "-p", $ComposeProject) + $Arguments)
}

function Invoke-Psql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(
            $Sql | & docker compose -p $ComposeProject exec -T postgres psql `
                --no-psqlrc -v "ON_ERROR_STOP=1" -U $env:POSTGRES_USER -d $SmokeDatabase 2>&1
        )
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Smoke fixture SQL failed.`n$($output -join [Environment]::NewLine)"
    }
}

function Invoke-PsqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(
            $Sql | & docker compose -p $ComposeProject exec -T postgres psql `
                --no-psqlrc -v "ON_ERROR_STOP=1" -U $env:POSTGRES_USER -d $SmokeDatabase `
                --tuples-only --no-align 2>&1
        )
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Smoke query failed.`n$($output -join [Environment]::NewLine)"
    }
    $lines = @(
        $output |
            ForEach-Object { "$_".Trim() } |
            Where-Object {
                $_ -ne "" -and
                $_ -notmatch '^(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP)\b' -and
                $_ -notmatch '^(NOTICE|WARNING):'
            }
    )
    if ($lines.Count -eq 0) {
        throw "Smoke query returned no value."
    }
    return $lines[-1]
}

function Assert-Equal {
    param(
        [AllowNull()][object]$Actual,
        [AllowNull()][object]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $matches = if ($null -eq $Expected) {
        $null -eq $Actual
    } elseif ($Expected -is [bool]) {
        $null -ne $Actual -and [bool]$Actual -eq $Expected
    } else {
        "$Actual" -ceq "$Expected"
    }
    if (-not $matches) {
        throw "Assertion failed: $Label. Expected '$Expected', got '$Actual'."
    }
    Write-Host "  PASS: $Label"
}

function Assert-PortAvailable {
    param([Parameter(Mandatory = $true)][int]$Port)

    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "Port $Port is already listening. Runtime smoke did not start any containers."
    }
}

function Assert-NoExistingEdspContainers {
    Write-Host "Checking existing Docker containers for compose project '$ComposeProject' before starting runtime smoke..."
    $projectNames = @(Invoke-DockerCapture -Arguments @(
        "ps", "-a", "--filter", "label=com.docker.compose.project=$ComposeProject", "--format", "{{.Names}}"
    ))
    Invoke-ComposeVisible -Arguments @("ps", "-a")

    if ($projectNames.Count -gt 0) {
        $names = ($projectNames | Sort-Object -Unique) -join ", "
        throw @"
Existing runtime smoke containers were detected for compose project '$ComposeProject': $names
The runtime smoke script will not reuse, stop, remove, or replace existing containers from this project.
Use a different ComposeProject and host ports for another smoke run, or manually confirm how to handle the old smoke containers.
A stopped project is still detected by this check, so docker compose -p $ComposeProject stop is not enough to rerun with the same project.
Do not use docker compose -p $ComposeProject down -v, docker volume rm, docker volume prune, or docker rm.
"@
    }
}

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Label,
        [int]$Attempts = 60
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Host "$Label ready: $Uri"
                return
            }
        } catch {
            if ($attempt -eq $Attempts) {
                throw "$Label did not become ready at $Uri. Last error: $($_.Exception.Message)"
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "$Label did not become ready at $Uri."
}

function New-SmokeFixture {
    param([Parameter(Mandatory = $true)][string]$Scenario)

    Require-SafeIdentifier -Value $Scenario -Label "Scenario"
    $key = "${Scenario}_$runId"
    $tableName = "events_$key"
    $externalId = "SMOKE_$($Scenario.ToUpperInvariant())_$($runId.ToUpperInvariant())"
    $sourceName = "Runtime smoke $key"
    $planName = "Runtime smoke plan $key"
    $schemaIdentifier = Quote-Identifier -Value $SmokeSchema
    $tableIdentifier = Quote-Identifier -Value $tableName

    $sourceConfig = [ordered]@{
        host = "postgres"
        port = 5432
        database = $SmokeDatabase
        username = $env:POSTGRES_USER
        password = $env:POSTGRES_PASSWORD
    } | ConvertTo-Json -Compress

    Invoke-Psql -Sql @"
create schema if not exists $schemaIdentifier;
create table $schemaIdentifier.$tableIdentifier (
    id varchar(160) primary key,
    create_time varchar(40) not null,
    event_name varchar(200) not null,
    user_account varchar(100) not null,
    host_name varchar(100) not null,
    risk_level varchar(32) not null
);
insert into $schemaIdentifier.$tableIdentifier(id, create_time, event_name, user_account, host_name, risk_level)
values ($(Quote-SqlText -Value $externalId), '2026-05-20 10:30:00', 'Runtime transform smoke', 'runtime-smoke-user', 'SMOKE-HOST-01', 'high');
"@

    $dataSourceId = Invoke-PsqlScalar -Sql @"
insert into data_sources(name, source_type, connection_kind, description, config_json, status, enabled)
values (
    $(Quote-SqlText -Value $sourceName),
    'postgresql',
    'database',
    'Transform runtime smoke fixture',
    cast($(Quote-SqlText -Value $sourceConfig) as jsonb),
    'active',
    true
)
returning id;
"@

    $scanRunId = Invoke-PsqlScalar -Sql @"
insert into schema_scan_runs(
    data_source_id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
)
values ($dataSourceId, 'success', 1, 1, 0, 6, 6)
returning id;
"@

    $tableId = Invoke-PsqlScalar -Sql @"
insert into schema_tables(
    data_source_id, scan_run_id, schema_name, table_name, category, confirmation_status, lifecycle_status
)
values ($dataSourceId, $scanRunId, $(Quote-SqlText -Value $SmokeSchema), $(Quote-SqlText -Value $tableName), 'alert_table', 'confirmed', 'active')
returning id;
"@

    Invoke-Psql -Sql @"
insert into schema_fields(
    schema_table_id, scan_run_id, field_name, field_type, sample_value, ordinal_position, confidence, lifecycle_status
)
values
    ($tableId, $scanRunId, 'id', 'varchar', $(Quote-SqlText -Value $externalId), 1, 95, 'active'),
    ($tableId, $scanRunId, 'create_time', 'timestamp', '2026-05-20 10:30:00', 2, 95, 'active'),
    ($tableId, $scanRunId, 'event_name', 'varchar', 'Runtime transform smoke', 3, 95, 'active'),
    ($tableId, $scanRunId, 'user_account', 'varchar', 'runtime-smoke-user', 4, 95, 'active'),
    ($tableId, $scanRunId, 'host_name', 'varchar', 'SMOKE-HOST-01', 5, 95, 'active'),
    ($tableId, $scanRunId, 'risk_level', 'varchar', 'high', 6, 95, 'active');
"@

    $plan = [ordered]@{
        version = "ingestion-plan-v1"
        mode = "database_polling"
        mainTable = $tableName
        schemaTableId = [long]$tableId
        cursorField = "create_time"
        fieldMappings = [ordered]@{
            id = "externalId"
            create_time = "occurredAt"
            event_name = "title"
            user_account = "actor"
            host_name = "assetRef"
            risk_level = "severity"
        }
        dedupStrategy = [ordered]@{
            type = "external_id"
            fields = @("id")
            fallback = "composite"
        }
        syncStrategy = [ordered]@{
            type = "polling"
            cursorField = "create_time"
            shadowOnly = $true
            enabled = $false
        }
        risks = @()
        requiredFieldsMissing = @()
    } | ConvertTo-Json -Depth 8 -Compress

    $planId = Invoke-PsqlScalar -Sql @"
insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
values ($dataSourceId, $scanRunId, $(Quote-SqlText -Value $planName), 'shadow_ready', cast($(Quote-SqlText -Value $plan) as jsonb))
returning id;
"@

    $shadowRunId = Invoke-PsqlScalar -Sql @"
insert into ingestion_plan_shadow_runs(
    ingestion_plan_id, data_source_id, status, sample_limit, read_count, success_count, failed_count, report_json
)
values ($planId, $dataSourceId, 'passed', 10, 1, 1, 0, cast('{}' as jsonb))
returning id;
"@

    $activationId = Invoke-PsqlScalar -Sql @"
insert into ingestion_plan_activations(
    ingestion_plan_id, data_source_id, shadow_run_id, status, activated_by, activation_reason, config_json
)
values ($planId, $dataSourceId, $shadowRunId, 'active', 'runtime-smoke', 'runtime smoke fixture', cast('{}' as jsonb))
returning id;
"@

    return [pscustomobject]@{
        Scenario = $Scenario
        PlanId = [long]$planId
        ActivationId = [long]$activationId
        ExternalId = $externalId
    }
}

function Invoke-SyncOnce {
    param([Parameter(Mandatory = $true)][long]$ActivationId)

    $body = '{"sampleLimit":10,"operatorName":"runtime-smoke"}'
    $response = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:$FrontendPort/api/core/ingestion-plan-activations/$ActivationId/sync-once" `
        -ContentType "application/json" -Body $body -TimeoutSec 30
    Assert-Equal -Actual $response.success -Expected $true -Label "API response success envelope"
    return $response.data
}

function Get-Observation {
    param([Parameter(Mandatory = $true)][pscustomobject]$Fixture)

    $externalIdLiteral = Quote-SqlText -Value $Fixture.ExternalId
    $json = Invoke-PsqlScalar -Sql @"
select json_build_object(
    'status', sync.status,
    'mode', sync.report_json #>> '{transformRuntime,mode}',
    'remoteAttempted', (sync.report_json #>> '{transformRuntime,remoteAttempted}')::boolean,
    'remoteSucceeded', (sync.report_json #>> '{transformRuntime,remoteSucceeded}')::boolean,
    'fallbackUsed', (sync.report_json #>> '{transformRuntime,fallbackUsed}')::boolean,
    'failurePresent', (sync.report_json->'transformRuntime') ? 'failureType',
    'failureType', sync.report_json #>> '{transformRuntime,failureType}',
    'rawCount', (select count(*) from raw_events where external_id = $externalIdLiteral),
    'standardCount', (select count(*) from standard_events where external_id = $externalIdLiteral),
    'externalId', (select external_id from standard_events where external_id = $externalIdLiteral order by id desc limit 1),
    'severity', (select severity from standard_events where external_id = $externalIdLiteral order by id desc limit 1),
    'actor', (select actor from standard_events where external_id = $externalIdLiteral order by id desc limit 1)
)::text
from ingestion_plan_sync_runs sync
where sync.activation_id = $($Fixture.ActivationId)
order by sync.id desc
limit 1;
"@
    return $json | ConvertFrom-Json
}

function Assert-SmokeResult {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][pscustomobject]$ApiResult,
        [Parameter(Mandatory = $true)][pscustomobject]$Observation,
        [Parameter(Mandatory = $true)][pscustomobject]$Fixture,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][bool]$RemoteSucceeded,
        [Parameter(Mandatory = $true)][bool]$FallbackUsed,
        [AllowNull()][string]$FailureType,
        [Parameter(Mandatory = $true)][long]$RawCount,
        [Parameter(Mandatory = $true)][long]$StandardCount
    )

    $expectedFailureType = if ([string]::IsNullOrEmpty($FailureType)) { $null } else { $FailureType }
    Write-Host "Validating $Label..."
    Assert-Equal -Actual $ApiResult.status -Expected $Status -Label "$Label API status"
    Assert-Equal -Actual $ApiResult.report.transformRuntime.mode -Expected $Mode -Label "$Label API runtime mode"
    Assert-Equal -Actual $Observation.status -Expected $Status -Label "$Label stored sync status"
    Assert-Equal -Actual $Observation.mode -Expected $Mode -Label "$Label stored runtime mode"
    Assert-Equal -Actual $Observation.remoteAttempted -Expected $true -Label "$Label remoteAttempted"
    Assert-Equal -Actual $Observation.remoteSucceeded -Expected $RemoteSucceeded -Label "$Label remoteSucceeded"
    Assert-Equal -Actual $Observation.fallbackUsed -Expected $FallbackUsed -Label "$Label fallbackUsed"
    Assert-Equal -Actual $Observation.failurePresent -Expected ($null -ne $expectedFailureType) -Label "$Label failureType presence"
    Assert-Equal -Actual $Observation.failureType -Expected $expectedFailureType -Label "$Label failureType"
    Assert-Equal -Actual ([long]$Observation.rawCount) -Expected $RawCount -Label "$Label raw_events count"
    Assert-Equal -Actual ([long]$Observation.standardCount) -Expected $StandardCount -Label "$Label standard_events count"
    if ($StandardCount -eq 1) {
        Assert-Equal -Actual $Observation.externalId -Expected $Fixture.ExternalId -Label "$Label external_id"
        Assert-Equal -Actual $Observation.severity -Expected "high" -Label "$Label severity"
        Assert-Equal -Actual $Observation.actor -Expected "runtime-smoke-user" -Label "$Label actor"
    }
    Write-Host "$Label`: PASS"
}

function Write-RuntimeDiagnostics {
    if (-not $runtimeTouched) {
        return
    }
    Write-Host "Runtime diagnostics:"
    try {
        Invoke-ComposeVisible -Arguments @("ps", "-a")
        Invoke-ComposeVisible -Arguments @("logs", "--tail=100", "edsp-core", "edsp-transform-service")
    } catch {
        Write-Warning "Unable to collect runtime diagnostics: $($_.Exception.Message)"
    }
}

Require-SafeIdentifier -Value $SmokeDatabase -Label "SmokeDatabase"
Require-SafeIdentifier -Value $SmokeSchema -Label "SmokeSchema"
if ($ComposeProject -notmatch '^[a-zA-Z0-9_-]+$') {
    throw "ComposeProject contains unsupported characters."
}
Assert-ArtifactRootIgnored

Push-Location $repoRoot
try {
    $failureStage = "preflight"
    Assert-NoExistingEdspContainers
    Assert-PortAvailable -Port $FrontendPort
    Assert-PortAvailable -Port $TransformPort

    $failureStage = "environment"
    $env:POSTGRES_DB = $SmokeDatabase
    $env:POSTGRES_USER = "edsp"
    $env:POSTGRES_PASSWORD = "edsp-transform-smoke-password"
    $env:FRONTEND_PORT = "$FrontendPort"
    $env:TRANSFORM_SERVICE_PORT = "$TransformPort"
    $env:EDSP_DEMO_ENABLED = "false"
    $env:EDSP_TRANSFORM_REMOTE_BASE_URL = "http://edsp-transform-service:8085"
    $env:EDSP_TRANSFORM_REMOTE_TIMEOUT_MS = "1000"
    $env:EDSP_TRANSFORM_REMOTE_SHADOW_ENABLED = "false"
    $env:EDSP_TRANSFORM_RUNTIME_MODE = "remote"

    $failureStage = "compose_config"
    Write-Host "Validating Docker Compose configuration..."
    Invoke-ComposeVisible -Arguments @("config", "--quiet")

    $failureStage = "runtime_start_remote"
    Write-Host "Starting isolated runtime smoke services in remote mode..."
    $runtimeTouched = $true
    Invoke-ComposeVisible -Arguments @("up", "--build", "-d", "postgres")
    Invoke-ComposeVisible -Arguments @("up", "--build", "-d", "edsp-transform-service", "edsp-core", "edsp-gateway", "frontend")
    Wait-HttpReady -Uri "http://127.0.0.1:$FrontendPort/api/core/overview" -Label "edsp-core via frontend" -Attempts $ReadyAttempts
    Wait-HttpReady -Uri "http://127.0.0.1:$TransformPort/actuator/health" -Label "edsp-transform-service" -Attempts $ReadyAttempts

    $failureStage = "remote_success"
    Write-Host "Running remote success fixture..."
    $remoteSuccess = New-SmokeFixture -Scenario "remote_success"
    $remoteSuccessApi = Invoke-SyncOnce -ActivationId $remoteSuccess.ActivationId
    $remoteSuccessObservation = Get-Observation -Fixture $remoteSuccess
    Assert-SmokeResult -Label "Remote success" -ApiResult $remoteSuccessApi -Observation $remoteSuccessObservation `
        -Fixture $remoteSuccess -Status "passed" -Mode "remote" -RemoteSucceeded $true -FallbackUsed $false `
        -FailureType $null -RawCount 1 -StandardCount 1
    $scenarioResults.remoteSuccess = "PASS"

    $failureStage = "remote_unavailable"
    Write-Host "Running remote unavailable fixture..."
    $remoteUnavailable = New-SmokeFixture -Scenario "remote_unavailable"
    Invoke-ComposeVisible -Arguments @("stop", "edsp-transform-service")
    $transformStopped = $true
    $remoteUnavailableApi = Invoke-SyncOnce -ActivationId $remoteUnavailable.ActivationId
    $remoteUnavailableObservation = Get-Observation -Fixture $remoteUnavailable
    Assert-SmokeResult -Label "Remote unavailable" -ApiResult $remoteUnavailableApi -Observation $remoteUnavailableObservation `
        -Fixture $remoteUnavailable -Status "failed" -Mode "remote" -RemoteSucceeded $false -FallbackUsed $false `
        -FailureType "remote_unavailable" -RawCount 0 -StandardCount 0
    $scenarioResults.remoteUnavailable = "PASS"

    $failureStage = "fallback_unavailable"
    Write-Host "Running fallback unavailable fixture..."
    $env:EDSP_TRANSFORM_RUNTIME_MODE = "fallback"
    Invoke-ComposeVisible -Arguments @("up", "--build", "-d", "--force-recreate", "edsp-core")
    Invoke-ComposeVisible -Arguments @("restart", "edsp-gateway", "frontend")
    Wait-HttpReady -Uri "http://127.0.0.1:$FrontendPort/api/core/overview" -Label "edsp-core fallback mode" -Attempts $ReadyAttempts
    $fallbackUnavailable = New-SmokeFixture -Scenario "fallback_unavailable"
    $fallbackUnavailableApi = Invoke-SyncOnce -ActivationId $fallbackUnavailable.ActivationId
    $fallbackUnavailableObservation = Get-Observation -Fixture $fallbackUnavailable
    Assert-SmokeResult -Label "Fallback unavailable" -ApiResult $fallbackUnavailableApi -Observation $fallbackUnavailableObservation `
        -Fixture $fallbackUnavailable -Status "passed" -Mode "fallback" -RemoteSucceeded $false -FallbackUsed $true `
        -FailureType "remote_unavailable" -RawCount 1 -StandardCount 1
    $scenarioResults.fallbackUnavailable = "PASS"

    $failureStage = $null
    $scenarioResults.transformRuntimeVerification = "PASS"
    Write-Host "transformRuntime verification: PASS"
} catch {
    $failure = $_
    $failureType = $_.Exception.GetType().Name
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Collect-SmokeArtifacts
    Write-RuntimeDiagnostics
} finally {
    if ($runtimeTouched -and $transformStopped -and $FinalAction -eq "Keep") {
        Write-Host "Restoring the transform service stopped by this smoke run..."
        try {
            Invoke-ComposeVisible -Arguments @("start", "edsp-transform-service")
        } catch {
            Write-Warning "Unable to restore edsp-transform-service: $($_.Exception.Message)"
        }
    }
    if ($runtimeTouched -and $FinalAction -eq "Stop") {
        Write-Host "Stopping runtime smoke containers without deleting containers or volumes..."
        try {
            Invoke-ComposeVisible -Arguments @("stop")
        } catch {
            Write-Warning "Unable to stop runtime smoke containers: $($_.Exception.Message)"
        }
    }
    Write-SmokeSummary
    if ($runtimeTouched) {
        if ($FinalAction -eq "Stop") {
            Write-Host "Runtime smoke containers were stopped and retained for inspection:"
        } else {
            Write-Host "Runtime smoke containers remain available for inspection:"
        }
        try {
            Invoke-ComposeVisible -Arguments @("ps", "-a")
        } catch {
            Write-Warning "Unable to list runtime containers: $($_.Exception.Message)"
        }
        Write-Host "Validation database: $SmokeDatabase"
        Write-Host "To stop containers without deleting volumes, run: docker compose -p $ComposeProject stop"
        Write-Host "Stopped containers are retained and will still block rerun with the same ComposeProject; use a new ComposeProject/ports for another smoke run."
        Write-Host "Do not use docker compose -p $ComposeProject down -v, docker volume rm, docker volume prune, or docker rm."
    }
    Pop-Location
}

if ($null -ne $failure) {
    exit 1
}

Write-Host "Remote success: PASS"
Write-Host "Remote unavailable: PASS"
Write-Host "Fallback unavailable: PASS"
Write-Host "transformRuntime verification: PASS"
