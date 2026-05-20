$ports = @(8080, 8081, 8082, 8083, 8084)

foreach ($port in $ports) {
    $connection = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($connection) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        Write-Host "Port $port listening by PID $($connection.OwningProcess) $($process.ProcessName)"
    } else {
        Write-Host "Port $port not listening"
    }
}
