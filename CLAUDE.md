# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Superpowers Skills

This project uses [Superpowers](https://github.com/obra/superpowers). Skills are located at `/Users/bhaskar/superpowers/skills/`.

At the start of every session, invoke the `using-superpowers` skill before doing anything else — including asking clarifying questions. Available skills:

| Skill | When to use |
|-------|-------------|
| `brainstorming` | Before writing any code — spec out what to build |
| `writing-plans` | Create an implementation plan after brainstorming |
| `executing-plans` | Work through a plan task by task |
| `test-driven-development` | Any feature implementation |
| `systematic-debugging` | When something is broken |
| `verification-before-completion` | Before saying a task is done |
| `subagent-driven-development` | Launch parallel agents for independent tasks |
| `requesting-code-review` | Before merging a branch |
| `finishing-a-development-branch` | Wrap up a feature branch |
| `using-git-worktrees` | When working on multiple features simultaneously |

## Build & Run Commands

```bash
# Build (skip tests — requires live DB)
mvn clean package -DskipTests

# Run locally (env vars must be exported first)
source .env && java -jar target/mmabot.jar

# Run a single test class
mvn test -Dtest=AttendanceServiceTest

# Run all tests
mvn test

# Tail logs in production
journalctl -u mmabot -f
```

## Environment Setup

All config is env-var backed. Copy `.env.example` → `.env` and fill in real values before running.

Required vars: `META_ACCESS_TOKEN`, `META_PHONE_NUMBER_ID`, `META_VERIFY_TOKEN`, `ADMIN_PHONE`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

DB must exist before starting: `mysql -u root -p < sql/schema.sql`

## Architecture

### Request Flow

```
WhatsApp User
  → Meta Cloud API (POST /webhook)
    → WebhookController   — validates X-Hub-Signature-256, extracts phone + text
      → CommandRouter     — checks admin vs member, routes to service
        → AttendanceService / MemberService / AdminService
          → WhatsAppClient  — sends reply via Meta Graph API
```

### Admin Identity

Admin is identified **solely by phone number** (`ADMIN_PHONE` env var). There is no auth token or session. `CommandRouter` compares the sender's phone against this value on every message.

### Multi-Step Flows

`SessionService` holds an in-memory `ConcurrentHashMap<phone, Session>`. When a multi-step command starts (e.g. `/addmember`), a session is stored. On the **next incoming message** from that phone, `CommandRouter` detects the pending session and delegates to `AdminService.continueSession()` instead of normal routing. Sessions are cleared on completion or on restart (intentional — user just re-issues the command).

### Check-in Integrity

`AttendanceService.checkIn()` enforces four guards in order:
1. Member status `ACTIVE` + payment `PAID`
2. Current IST time within ±30 min of a `gym_classes` entry for today's `day_of_week`
3. `existsByMemberAndClassDate` pre-check
4. DB `UNIQUE KEY (member_id, class_date)` — `DataIntegrityViolationException` caught as race-condition safety net

Admin `/mark` bypasses all guards (`adminMarkAttendance` upserts directly).

### Scheduled Jobs

All jobs live in `SchedulerService`, annotated with `@Scheduled(cron = "...", zone = "Asia/Kolkata")`. The 9 AM job handles three expiry states in one pass (7-day warning, 1-day warning, expired today) — keep them in the same method to avoid triple-querying members.

### Key Conventions

- All time/date logic uses `ZoneId.of("Asia/Kolkata")` — never `LocalDate.now()` without the zone.
- `WhatsAppClient.sendText()` swallows exceptions and logs — callers do not handle send failures.
- Member search (`searchByNameOrPhone`) is case-insensitive LIKE on name + suffix LIKE on phone. Multi-result commands use `results.get(0)` — be explicit if this matters for a new command.
- `ddl-auto: validate` — schema changes require a manual `ALTER TABLE` or re-running `sql/schema.sql` on the target DB; Hibernate will not auto-migrate.
