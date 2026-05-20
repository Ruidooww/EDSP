$ErrorActionPreference = "Stop"

$root = Resolve-Path "$PSScriptRoot\.."
$javaHome = "C:\Program Files\Java\jdk-21.0.11"
$mavenHome = "C:\Program Files\Apache\Maven\apache-maven-3.9.15"
$logsDir = Join-Path $root "logs"

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
