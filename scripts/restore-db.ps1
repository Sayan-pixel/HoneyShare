# HoneyShare Database Restore Drill Script (PowerShell)
# Complies with Specification Section 14 (Disaster recovery drill)
param (
    [Parameter(Mandatory=$true)]
    [string]$BackupFile,
    [string]$ContainerName = "honeyshare-mysql",
    [string]$DbUser = "root",
    [string]$DbPass = "rootchangeme",
    [string]$DbName = "honeyshare",
    [int]$DbPort = 3307
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupFile)) {
    Write-Error "Backup file not found: $BackupFile"
}

Write-Host "==> Starting HoneyShare database restore drill from: $BackupFile" -ForegroundColor Cyan

$DockerRunning = (docker ps --filter "name=$ContainerName" --format "{{.Names}}") -eq $ContainerName

if ($DockerRunning) {
    Write-Host "==> Restoring into Docker container: $ContainerName" -ForegroundColor Yellow
    Get-Content $BackupFile | docker exec -i $ContainerName mysql -u $DbUser "-p$DbPass" $DbName
} else {
    Write-Host "==> Restoring via local mysql client..." -ForegroundColor Yellow
    Get-Content $BackupFile | mysql -h 127.0.0.1 -P $DbPort -u $DbUser "-p$DbPass" $DbName
}

Write-Host "==> Verifying table counts..." -ForegroundColor Cyan
if ($DockerRunning) {
    docker exec $ContainerName mysql -u $DbUser "-p$DbPass" -e "SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='$DbName';"
} else {
    mysql -h 127.0.0.1 -P $DbPort -u $DbUser "-p$DbPass" -e "SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='$DbName';"
}

Write-Host "==> Restore drill completed successfully!" -ForegroundColor Green

