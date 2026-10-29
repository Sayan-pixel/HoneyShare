# Operational Runbook: Rate Limiting, Abuse Controls & Security

**Reference:** Sections 12, 15, and 18  

---

## 1. Overview
HoneyShare enforces strict multi-tiered rate limiting to protect authentication endpoints, upload sessions, and email/SMS channels from brute-force attacks and denial-of-service attempts.

---

## 2. Default Rate Limits

| Endpoint / Action | Key / Scope | Limit / Window | Action on Exceeded |
|---|---|---|---|
| `POST /api/v1/auth/login` | IP address + email | 5 attempts per 15 min | HTTP 429 (`RATE_LIMIT_EXCEEDED`) |
| `POST /api/v1/auth/mfa/verify` | User ID | 5 attempts per 15 min | HTTP 429 (`RATE_LIMIT_EXCEEDED`) |
| `POST /api/v1/auth/register` | IP address | 10 attempts per hour | HTTP 429 (`RATE_LIMIT_EXCEEDED`) |
| `POST /api/v1/transfers` | User ID | 30 transfers per hour | HTTP 429 (`RATE_LIMIT_EXCEEDED`) |
| `POST /api/v1/invitations` | Administrator ID | 50 invitations per hour | HTTP 429 (`RATE_LIMIT_EXCEEDED`) |

---

## 3. Investigating Security Alerts

### Step 1: Audit Log Query for Brute-Force Attempts
```sql
SELECT user_id, action, target_type, target_id, ip_address, created_at
FROM audit_events
WHERE action IN ('LOGIN_FAILED', 'MFA_VERIFY_FAILED', 'RATE_LIMIT_TRIGGERED')
  AND created_at > DATE_SUB(NOW(), INTERVAL 1 HOUR)
ORDER BY created_at DESC;
```

### Step 2: Identify High-Frequency Origin IPs
```sql
SELECT ip_address, COUNT(*) AS failed_count
FROM audit_events
WHERE action = 'LOGIN_FAILED'
  AND created_at > DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY ip_address
HAVING failed_count > 10
ORDER BY failed_count DESC;
```

### Step 3: Temporary Account Lockout or Administrative Action
If an account is under targeted attack:
```sql
-- Disable user account temporarily
UPDATE users SET status = 'SUSPENDED', updated_at = NOW() WHERE id = 'TARGET_USER_ID';

-- Revoke all active sessions
UPDATE user_sessions SET revoked_at = NOW() WHERE user_id = 'TARGET_USER_ID';
```
The user will be immediately logged out and unable to authenticate until reviewed.

