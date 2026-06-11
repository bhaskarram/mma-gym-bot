# Architecture — MMA Gym WhatsApp Bot

## System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Hetzner CX22 VPS                           │
│                                                                     │
│   WhatsApp User                                                     │
│        │                                                            │
│        ▼                                                            │
│   Meta Cloud API ──POST /webhook──► Nginx (443 SSL)                 │
│                                          │                          │
│                                          ▼                          │
│                                   Spring Boot :8080                 │
│                                          │                          │
│                         ┌────────────────┼─────────────────┐        │
│                         ▼                ▼                 ▼        │
│                  WebhookController  SchedulerService   /health      │
│                         │                │                          │
│                         ▼                │                          │
│                   CommandRouter          │                          │
│                    /     |     \         │                          │
│                   ▼      ▼      ▼        │                          │
│            Attendance Member  Admin      │                          │
│            Service   Service  Service ◄──┘                          │
│                   \      |      /                                   │
│                    ▼     ▼     ▼                                    │
│                      MySQL 8.x                                      │
│                    (same VPS)                                       │
│                         │                                           │
│                         ▼                                           │
│                   WhatsAppClient ──► Meta Graph API                 │
│                                      (outbound reply)               │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Component Responsibilities

### Controller Layer

**`WebhookController`**
- `GET /webhook` — Meta verification handshake (returns `hub.challenge` if `hub.verify_token` matches)
- `POST /webhook` — validates `X-Hub-Signature-256` (HMAC-SHA256 of raw body), extracts `phone` + `text` from Meta payload, hands off to `CommandRouter`
- `GET /health` — public liveness check, no auth

### Routing Layer

**`CommandRouter`**
Central dispatcher. On every inbound message:
1. Checks `SessionService` — if a multi-step flow is active for this phone, routes to `AdminService.continueSession()` and returns early
2. Looks up sender phone in `members` table
3. Unregistered non-admin → rejection message
4. Admin phone (`ADMIN_PHONE` env var) → admin command set
5. Registered member → member command set

No business logic lives here. It only parses the first token of the message and delegates.

### Service Layer

**`AttendanceService`**
Owns the most critical path — `/checkin` validation:
```
checkIn()
  ├── status + payment guard
  ├── time-window check (±30 min around gym_classes.class_time for today's day_of_week)
  ├── existsByMemberAndClassDate pre-check
  └── save() — UNIQUE constraint catches race conditions → treated as duplicate
```
Also owns `computeStreak()` (used by both `MemberService` and `SchedulerService`) and `adminMarkAttendance()` which bypasses all guards.

**`MemberService`**
Handles all member-facing read commands and progress logging. Stateless — reads from DB and formats WhatsApp reply strings. No writes except `Progress` inserts.

**`AdminService`**
Handles all admin commands. Also owns the `/addmember` multi-step flow via `SessionService`. The `continueSession()` method is a state machine keyed on `session.action()`:
```
ADD_MEMBER_NAME → ADD_MEMBER_PHONE → ADD_MEMBER_PLAN → save + welcome message
```

**`SchedulerService`**
All `@Scheduled` jobs. Runs independently of the webhook path. Sends outbound messages directly via `WhatsAppClient`. The 9 AM payment job handles three expiry states (7d warning, 1d warning, expired today) in a single method to share one query pass over members.

**`SessionService`**
Thin wrapper over `ConcurrentHashMap<String, Session>`. Holds pending multi-step conversation state keyed by phone number. Resets on JVM restart (intentional — user re-issues the command).

### Client Layer

**`WhatsAppClient`**
Single outbound HTTP client to Meta Graph API. Uses `RestTemplate`. All exceptions are caught and logged — callers never handle send failures. Stateless and injectable everywhere.

---

## Data Model

```
members
  id, name, phone (UNIQUE), join_date
  plan_type (MONTHLY|QUARTERLY|ANNUAL)
  plan_expiry, payment_status (PAID|UNPAID|EXPIRED)
  status (ACTIVE|INACTIVE|SUSPENDED)
  belt_rank (WHITE|BLUE|PURPLE|BROWN|BLACK)
  emergency_contact, injuries
       │
       │ 1:N
       ▼
attendance
  id, member_id (FK), class_date
  status (PRESENT|ABSENT|LATE)
  marked_by (SELF|ADMIN|QR)
  class_type
  UNIQUE KEY (member_id, class_date)   ← fraud prevention
       │
       │ 1:N
       ▼
progress
  id, member_id (FK), log_date
  weight_kg, metric_type, value, notes

gym_classes                             ← schedule reference, not linked to attendance rows
  id, class_name, day_of_week (1–7)
  class_time, max_capacity, coach, is_active
```

### Key Constraints

- `UNIQUE KEY (member_id, class_date)` on `attendance` is the primary fraud control — one check-in per member per day, enforced at DB level
- `ddl-auto: validate` — Hibernate never touches the schema; all DDL is manual via `sql/schema.sql`
- `gym_classes.day_of_week` uses ISO convention (1 = Monday, 7 = Sunday), matched against `LocalDate.getDayOfWeek().getValue()` in `AttendanceService`

---

## Message Flow — Inbound

```
Meta POST /webhook
  │
  ├── X-Hub-Signature-256 invalid?  → 403, drop
  │
  ├── payload.entry[].changes[].value.messages[]
  │     └── type != "text"?  → skip (ignore images, reactions, etc.)
  │
  └── for each text message:
        phone = message.from
        text  = message.text.body.trim()
        CommandRouter.route(phone, text)
```

## Message Flow — Outbound

Every service method ends by calling `WhatsAppClient.sendText(phone, message)`. This is a synchronous HTTP POST to:
```
POST https://graph.facebook.com/v21.0/{META_PHONE_NUMBER_ID}/messages
Authorization: Bearer {META_ACCESS_TOKEN}
{
  "messaging_product": "whatsapp",
  "to": "<phone>",
  "type": "text",
  "text": { "body": "<message>" }
}
```
Failures are logged but not retried. Meta free tier allows 1,000 messages/day — well above gym capacity.

---

## Scheduled Jobs Timeline

```
06:00 IST  ──► sendClassReminders()      All active+paid members, for today's classes
09:00 IST  ──► sendPaymentWarnings()     7-day, 1-day, expired-today — one pass
10:00 IST  ──► sendMissedClassAlerts()   Admin alert for 5+ consecutive absences
20:00 IST  ──► sendDailySummary()        Admin: check-ins vs active member count
01st 08:00 ──► sendMonthlyReport()       Admin: previous month stats
```

All cron expressions include `zone = "Asia/Kolkata"` — never rely on JVM default timezone.

---

## Security Model

| Concern | Mechanism |
|---------|-----------|
| Webhook authenticity | `X-Hub-Signature-256` HMAC-SHA256 validated on every POST |
| Webhook registration | `META_VERIFY_TOKEN` matched on GET handshake |
| Admin identity | Phone number comparison against `ADMIN_PHONE` env var |
| Secrets | All sensitive values in `.env` file, loaded as system env vars — never in `application.yml` |
| Transport | Nginx terminates TLS; Spring Boot only listens on `localhost:8080` |

No password, session token, or JWT is used. Admin trust is entirely phone-number-based — protect `ADMIN_PHONE` and VPS SSH access accordingly.

---

## Deployment Architecture

```
Internet
    │
    ▼
Nginx (port 443, Let's Encrypt SSL)
    │  proxy_pass http://localhost:8080
    ▼
Spring Boot JAR  ← managed by systemd (auto-restart on crash)
    │
    ├── MySQL 8.x (localhost:3306)
    └── Outbound HTTPS → api.graph.facebook.com
```

The JAR, `.env`, and `systemd` unit file all live under `/home/ubuntu/mmabot/` on the Hetzner CX22 (2 vCPU, 4 GB RAM, Ubuntu 22.04).

---

## Phase Boundaries

The codebase is built for four delivery phases. All Phase 1–3 code is present. Phase 4 (Cashfree payments) is not yet implemented.

| Phase | What's wired up |
|-------|----------------|
| 1 | `/checkin`, `/mystats`, `/help`, `/schedule`, class reminders |
| 2 | All admin commands, payment/missed schedulers |
| 3 | `/streak`, `/progress`, `/myrank`, `/promote`, belt system |
| 4 | Cashfree SDK, `/pay`, auto-confirm webhook — **not implemented** |

`payment_status` is currently set manually via `/addmember` (hardcoded to `PAID`) and expires via the 9 AM scheduler. Phase 4 will replace this with Cashfree webhook callbacks.
