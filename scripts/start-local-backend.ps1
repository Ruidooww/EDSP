$ErrorActionPreference = "Stop"

$root = Resolve-Path "$PSScriptRoot\.."
$logsDir = Join-Path $root "logs"

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        return Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
    }

    $candidate = Get-ChildItem "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($candidate) {
        return $candidate.FullName
    }

    throw "JDK 21 not found. Set JAVA_HOME or install JDK 21."
}

function Find-MavenHome {
    if ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME "bin\mvn.cmd"))) {
        return $env:MAVEN_HOME
    }

    $mvnCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mvnCommand) {
        $mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
    }
    if ($mvnCommand) {
        return Split-Path (Split-Path $mvnCommand.Source -Parent) -Parent
    }

    $candidate = Get-ChildItem "C:\Program Files\Apache" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\mvn.cmd") } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($candidate) {
        return $candidate.FullName
    }

    throw "Maven not found. Set MAVEN_HOME or install Maven."
}

$javaHome = Find-JavaHome
$mavenHome = Find-MavenHome

if (-not (Test-Path $javaHome)) {
    throw "JDK 21 not found at $javaHome"
}

if (-not (Test-Path $mavenHome)) {
    throw "Maven not found at $mavenHome"
}

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root "data") | Out-Null

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$mavenHome\bin;$env:Path"
$env:EDSP_DEMO_ENABLED = "true"

& "$PSScriptRoot\build-backend.ps1"

$services = @(
    @{ Name = "edsp-core"; Port = "8082" },
    @{ Name = "edsp-auth"; Port = "8081" },
    @{ Name = "edsp-alert"; Port = "8083" },
    @{ Name = "edsp-report"; Port = "8084" },
    @{ Name = "edsp-gateway"; Port = "8080" }
)

foreach ($service in $services) {
    $existing = Get-NetTCPConnection -LocalPort $service.Port -State Listen -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "$($service.Name) appears to already be listening on port $($service.Port)."
        continue
    }

    $jar = Join-Path $root "backend\$($service.Name)\target\$($service.Name)-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) {
        throw "Jar not found: $jar"
    }

    $out = Join-Path $logsDir "$($service.Name).out.log"
    $err = Join-Path $logsDir "$($service.Name).err.log"

    Start-Process `
        -FilePath "$javaHome\bin\java.exe" `
        -ArgumentList "-jar", "`"$jar`"", "--spring.profiles.active=local" `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput $out `
        -RedirectStandardError $err

    Write-Host "Started $($service.Name) on port $($service.Port)."
    Start-Sleep -Seconds 2
}

Write-Host "Local backend startup requested. Gateway: http://localhost:8080"
Write-Host "Logs: $logsDir"
