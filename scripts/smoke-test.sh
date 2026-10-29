#!/usr/bin/env bash
# HoneyShare End-to-End API Smoke Test (POSIX Bash)
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
MANAGEMENT_URL="${2:-http://localhost:8081}"

echo "==> Running HoneyShare API Smoke Test against: $BASE_URL (Management: $MANAGEMENT_URL)"

# 1. Health Check
echo -n "1. Testing $MANAGEMENT_URL/actuator/health... "
HEALTH_STATUS=$(curl -s -f "$MANAGEMENT_URL/actuator/health" | grep -o '"status":"UP"' || true)
if [ -n "$HEALTH_STATUS" ]; then
    echo "[PASS]"
else
    echo "[FAIL: could not reach health endpoint]"
fi

# 2. Web Client Index
echo -n "2. Testing Web Client root (/) and /index.html... "
HTML_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/index.html")
if [ "$HTML_STATUS" = "200" ]; then
    echo "[PASS]"
else
    echo "[FAIL: HTTP $HTML_STATUS]"
fi

# 3. Authentication Guard
echo -n "3. Verifying Protected Route Authentication Guard (/api/v1/users/me)... "
AUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/users/me" || true)
if [ "$AUTH_STATUS" = "401" ] || [ "$AUTH_STATUS" = "403" ]; then
    echo "[PASS: Rejected unauthenticated request with HTTP $AUTH_STATUS]"
else
    echo "[FAIL: Expected 401/403, got HTTP $AUTH_STATUS]"
fi

echo "==> Smoke test suite completed."

