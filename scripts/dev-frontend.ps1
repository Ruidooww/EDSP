$ErrorActionPreference = "Stop"

Push-Location "$PSScriptRoot\..\frontend"
try {
    if (-not (Test-Path "node_modules")) {
        npm install
    }

    npm run dev -- --host 0.0.0.0
}
finally {
    Pop-Location
}
