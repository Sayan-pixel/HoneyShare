# HoneyShare Invitation Token Generator
# Usage: .\scripts\create-invite.ps1

$ErrorActionPreference = "Stop"

# 1. Generate 32-character random secure token
$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$rawToken = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')

# 2. Compute SHA-256 hash
$hasher = [System.Security.Cryptography.SHA256]::Create()
$hashBytes = $hasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($rawToken))
$tokenHash = -join ($hashBytes | ForEach-Object { "{0:x2}" -f $_ })

# 3. Generate ULID for DB Primary Key
$ulid = [System.Guid]::NewGuid().ToString("N").Substring(0, 26).ToUpper()

# 4. Check if MySQL container is running
$container = docker ps -q --filter "name=honeyshare-mysql"
if (-not $container) {
    Write-Host "Starting MySQL container..." -ForegroundColor Yellow
    docker compose up -d honeyshare-mysql
    Start-Sleep -Seconds 3
}

# 5. Insert token into database
docker exec honeyshare-mysql mysql -u honeyshare -pchangeme -e "
INSERT INTO honeyshare.invitations (id, email_normalized, token_hash, created_by_user_id, created_at, expires_at)
VALUES ('$ulid', NULL, '$tokenHash', NULL, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY));
" | Out-Null

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " NEW INVITATION TOKEN GENERATED:" -ForegroundColor Green
Write-Host " $rawToken" -ForegroundColor Yellow -BackgroundColor Black
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Copy the yellow code above and paste it into the"
Write-Host "'Invitation Token' box on the Registration page!"
Write-Host ""

