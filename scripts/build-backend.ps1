$ErrorActionPreference = "Stop"

$javaHome = "C:\Program Files\Java\jdk-21.0.11"
$mavenHome = "C:\Program Files\Apache\Maven\apache-maven-3.9.15"

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
