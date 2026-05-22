$ErrorActionPreference = "Stop"

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

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$mavenHome\bin;$env:Path"

Push-Location "$PSScriptRoot\..\backend"
try {
    mvn clean package -DskipTests
}
finally {
    Pop-Location
}
