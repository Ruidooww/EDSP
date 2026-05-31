[CmdletBinding()]
param(
    [int]$PostgresPort = 15432,
    [string]$ComposeProject = "",
    [ValidateSet("Keep", "Stop")]
    [string]$FinalAction = "Stop",
    [string]$ArtifactRoot = "logs/alert-generation-postgresql-concurrency",
    [int]$ReadyAttempts = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $PSScriptRoot "docker-compose.alert-generation-postgresql-concurrency.yml"
$runId = "$(Get-Date -Format 'yyyyMMddHHmmss')_$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
if ([string]::IsNullOrWhiteSpace($ComposeProject)) {
    $ComposeProject = "edsp_alert_pg_verify_$runId"
}
$database = "edsp_alert_pg_verify"
$username = "edsp"
$password = "edsp-alert-pg-verify-password"
$artifactPath = Join-Path (Join-Path ([System.IO.Path]::GetTempPath()) "edsp-alert-pg-verify-artifacts") $runId
$testResultPath = Join-Path ([System.IO.Path]::GetTempPath()) "edsp-alert-pg-verify-$runId.json"
$runtimeTouched = $false
$verificationPassed = $false
$testResult = $null

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
        throw "Docker command failed."
    }
    return $output
}

function Invoke-Compose {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    return Invoke-DockerCapture -Arguments (@(
        "compose", "-p", $ComposeProject, "-f", $composeFile
    ) + $Arguments)
}

function Select-ArtifactPath {
    $relative = $ArtifactRoot.Replace('\', '/')
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git -C $repoRoot check-ignore -q -- $relative
        $ignored = $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($ignored) {
        $script:artifactPath = Join-Path (Join-Path $repoRoot $ArtifactRoot) $runId
        return
    }
    Write-Warning "ArtifactRoot is not ignored by git. Writing the safe summary to the OS temp directory."
}

function Assert-ComposeProjectAvailable {
    $containers = @(Invoke-DockerCapture -Arguments @(
        "ps", "-a", "--filter", "label=com.docker.compose.project=$ComposeProject", "--format", "{{.Names}}"
    ))
    if ($containers.Count -gt 0) {
        throw "ComposeProject already has containers. Use a new ComposeProject."
    }
}

function Assert-PortAvailable {
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new(
            [System.Net.IPAddress]::Loopback,
            $PostgresPort
        )
        $listener.Start()
    } catch {
        throw "PostgresPort is unavailable."
    } finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

function Wait-PostgresReady {
    for ($attempt = 1; $attempt -le $ReadyAttempts; $attempt++) {
        try {
            Invoke-Compose -Arguments @(
                "exec", "-T", "postgres", "pg_isready", "-U", $username, "-d", $database
            ) | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "PostgreSQL did not become ready."
}

function Test-HardeningApplied {
    $repositoryPath = Join-Path $repoRoot "backend/edsp-core/src/main/java/com/edsp/core/service/AlertRepository.java"
    return (Select-String -Path $repositoryPath -Pattern "on conflict do nothing" -Quiet)
}

function Save-Summary {
    param([Parameter(Mandatory = $true)][string]$Status)

    New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
    $summary = [ordered]@{
        verification = "alert_generation_postgresql_concurrency"
        status = $Status
        composeProject = $ComposeProject
        postgresPort = $PostgresPort
        testClass = "AlertRepositoryPostgresqlConcurrencyTest"
        createdAlertCount = if ($null -eq $testResult) { $null } else { $testResult.createdAlertCount }
        existingAlertCount = if ($null -eq $testResult) { $null } else { $testResult.existingAlertCount }
        notificationDeliveryCount = if ($null -eq $testResult) { $null } else { $testResult.notificationDeliveryCount }
        postgresConcurrencyRecovery = $Status
        hardeningApplied = Test-HardeningApplied
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $artifactPath "summary.json") -Encoding UTF8
}

if ($ComposeProject -notmatch '^[a-zA-Z0-9_-]+$') {
    throw "ComposeProject contains unsupported characters."
}
if ($PostgresPort -lt 1024 -or $PostgresPort -gt 65535) {
    throw "PostgresPort must be between 1024 and 65535."
}
if ($ReadyAttempts -lt 1) {
    throw "ReadyAttempts must be positive."
}

$previousPostgresDb = $env:POSTGRES_DB
$previousPostgresUser = $env:POSTGRES_USER
$previousPostgresPassword = $env:POSTGRES_PASSWORD
$previousVerifyPort = $env:EDSP_ALERT_PG_VERIFY_PORT
$previousVerifyUrl = $env:EDSP_ALERT_PG_VERIFY_URL
$previousVerifyUsername = $env:EDSP_ALERT_PG_VERIFY_USERNAME
$previousVerifyPassword = $env:EDSP_ALERT_PG_VERIFY_PASSWORD
$previousVerifyResultPath = $env:EDSP_ALERT_PG_VERIFY_RESULT_PATH

try {
    Select-ArtifactPath
    Assert-ComposeProjectAvailable
    Assert-PortAvailable

    $env:POSTGRES_DB = $database
    $env:POSTGRES_USER = $username
    $env:POSTGRES_PASSWORD = $password
    $env:EDSP_ALERT_PG_VERIFY_PORT = [string]$PostgresPort

    Invoke-Compose -Arguments @("up", "-d", "postgres") | ForEach-Object { Write-Host $_ }
    $runtimeTouched = $true
    Wait-PostgresReady

    $env:EDSP_ALERT_PG_VERIFY_URL = "jdbc:postgresql://127.0.0.1:$PostgresPort/$database"
    $env:EDSP_ALERT_PG_VERIFY_USERNAME = $username
    $env:EDSP_ALERT_PG_VERIFY_PASSWORD = $password
    $env:EDSP_ALERT_PG_VERIFY_RESULT_PATH = $testResultPath

    Push-Location (Join-Path $repoRoot "backend")
    try {
        & mvn -pl edsp-core -am "-Dtest=AlertRepositoryPostgresqlConcurrencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
        if ($LASTEXITCODE -ne 0) {
            throw "PostgreSQL concurrency verification test failed."
        }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $testResultPath)) {
        throw "PostgreSQL concurrency verification result was not produced."
    }
    $testResult = Get-Content -Raw -LiteralPath $testResultPath | ConvertFrom-Json
    $verificationPassed = $true
    Save-Summary -Status "PASS"
    Write-Host "PostgreSQL concurrency verification PASS."
    Write-Host "Safe summary: $(Join-Path $artifactPath 'summary.json')"
} catch {
    Save-Summary -Status "FAIL"
    Write-Error "PostgreSQL concurrency verification FAIL. Inspect the Maven output and safe summary."
    throw
} finally {
    if ($runtimeTouched -and $FinalAction -eq "Stop") {
        try {
            Invoke-Compose -Arguments @("stop", "postgres") | ForEach-Object { Write-Host $_ }
        } catch {
            Write-Warning "Unable to stop the verification PostgreSQL container."
        }
    }
    if (Test-Path -LiteralPath $testResultPath) {
        Remove-Item -LiteralPath $testResultPath -Force
    }
    $env:POSTGRES_DB = $previousPostgresDb
    $env:POSTGRES_USER = $previousPostgresUser
    $env:POSTGRES_PASSWORD = $previousPostgresPassword
    $env:EDSP_ALERT_PG_VERIFY_PORT = $previousVerifyPort
    $env:EDSP_ALERT_PG_VERIFY_URL = $previousVerifyUrl
    $env:EDSP_ALERT_PG_VERIFY_USERNAME = $previousVerifyUsername
    $env:EDSP_ALERT_PG_VERIFY_PASSWORD = $previousVerifyPassword
    $env:EDSP_ALERT_PG_VERIFY_RESULT_PATH = $previousVerifyResultPath
}

if (-not $verificationPassed) {
    exit 1
}
