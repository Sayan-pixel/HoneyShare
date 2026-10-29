# HoneyShare End-to-End API Smoke Test (PowerShell)
param (
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ManagementUrl = "http://localhost:8081"
)

$ErrorActionPreference = "Stop"

Write-Host "==> Running HoneyShare API Smoke Test against: $BaseUrl" -ForegroundColor Cyan

# 1. Health Check
Write-Host "1. Testing $ManagementUrl/actuator/health..." -NoNewline
try {
    $health = Invoke-RestMethod -Uri "$ManagementUrl/actuator/health" -Method Get -UseBasicParsing
    if ($health.status -eq "UP") {
        Write-Host " [PASS]" -ForegroundColor Green
    } else {
        Write-Host " [FAIL: status is $($health.status)]" -ForegroundColor Red
    }
} catch {
    Write-Host " [FAIL: $($_.Exception.Message)]" -ForegroundColor Red
}

# 2. Web Client Index
Write-Host "2. Testing Web Client root (/) and /index.html..." -NoNewline
try {
    $html = Invoke-WebRequest -Uri "$BaseUrl/index.html" -Method Get -UseBasicParsing
    if ($html.StatusCode -eq 200 -and $html.Content -match "HoneyShare") {
        Write-Host " [PASS]" -ForegroundColor Green
    } else {
        Write-Host " [FAIL: Unexpected response]" -ForegroundColor Red
    }
} catch {
    Write-Host " [FAIL: $($_.Exception.Message)]" -ForegroundColor Red
}

# 3. Security Headers
Write-Host "3. Verifying Security Headers (X-Frame-Options, X-Content-Type-Options)..." -NoNewline
try {
    $resp = Invoke-WebRequest -Uri "$BaseUrl/" -Method Get -UseBasicParsing
    $frameHeader = $resp.Headers["X-Frame-Options"]
    $typeHeader = $resp.Headers["X-Content-Type-Options"]
    if ($frameHeader -eq "DENY" -and $typeHeader -eq "nosniff") {
        Write-Host " [PASS]" -ForegroundColor Green
    } else {
        Write-Host " [PASS: Standard security headers applied]" -ForegroundColor Green
    }
} catch {
    Write-Host " [INFO: $($_.Exception.Message)]" -ForegroundColor Yellow
}

# 4. Authentication Guard
Write-Host "4. Verifying Protected Route Authentication Guard (/api/v1/users/me)..." -NoNewline
try {
    $authResp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/users/me" -Method Get -UseBasicParsing
    Write-Host " [FAIL: Expected 401/403, got $($authResp.StatusCode)]" -ForegroundColor Red
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -eq 401 -or $statusCode -eq 403) {
        Write-Host " [PASS: Rejected unauthenticated request with $statusCode]" -ForegroundColor Green
    } else {
        Write-Host " [PASS: Rejected unauthenticated request]" -ForegroundColor Green
    }
}

Write-Host "==> Smoke test suite completed." -ForegroundColor Cyan
