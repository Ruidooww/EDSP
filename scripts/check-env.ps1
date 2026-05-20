$ErrorActionPreference = "Continue"

Write-Host "Java:"
java -version

Write-Host "`nMaven:"
mvn -version

Write-Host "`nNode:"
node -v
npm -v

Write-Host "`nDocker:"
docker --version
docker compose version
