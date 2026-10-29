# HoneyShare Database Backup Script (PowerShell)
# Complies with Specification Section 14 (MySQL backup/restore drills)
param (
    [string]$OutputDir = "./backups",
    [string]$ContainerName = "honeyshare-mysql",
    [string]$DbUser = "root",
    [string]$DbPass = "rootchangeme",
    [string]$DbName = "honeyshare",
    [int]$DbPort = 3307
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupFile = Join-Path $OutputDir "honeyshare-backup-$Timestamp.sql"

Write-Host "==> Starting HoneyShare database backup..." -ForegroundColor Cyan

# Check if running via Docker or local mysqldump
$DockerRunning = (docker ps --filter "name=$ContainerName" --format "{{.Names}}") -eq $ContainerName

if ($DockerRunning) {
    Write-Host "==> Dumping from Docker container: $ContainerName" -ForegroundColor Green
    docker exec $ContainerName mysqldump -u $DbUser "-p$DbPass" --single-transaction --quick --routines --triggers $DbName > $BackupFile
} else {
    Write-Host "==> Dumping via local mysqldump binary..." -ForegroundColor Green
    mysqldump -h 127.0.0.1 -P $DbPort -u $DbUser "-p$DbPass" --single-transaction --quick --routines --triggers $DbName > $BackupFile
}

$FileSize = (Get-Item $BackupFile).Length
Write-Host "==> Backup completed successfully: $BackupFile ($([math]::Round($FileSize / 1KB, 2)) KB)" -ForegroundColor Green

