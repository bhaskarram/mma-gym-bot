# MMA Gym WhatsApp Bot

A WhatsApp-based gym management bot built with Java 17 + Spring Boot 3.x, Meta Cloud API, and MySQL. Replaces manual attendance tracking, screenshot payment confirmations, and ad-hoc reminders with an automated system operated entirely through WhatsApp.

**Monthly cost: ~₹700 (Hetzner VPS only — everything else is free)**

---

## Features

- **Attendance** — time-windowed check-in with duplicate prevention and fraud controls
- **Admin commands** — manage members, view reports, and override attendance via WhatsApp
- **Automated reminders** — class reminders, payment warnings, and missed-class alerts via Spring `@Scheduled`
- **Progress tracking** — members log and view personal training metrics
- **Multi-step flows** — conversational member registration with in-memory session state

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 (OpenJDK) |
| Framework | Spring Boot 3.2 |
| WhatsApp API | Meta Cloud API v21 (free) |
| Database | MySQL 8.x |
| Hosting | Hetzner CX22 (~₹700/month) |
| Reverse Proxy | Nginx + Certbot (Let's Encrypt) |

---

## Project Structure

```
mma-gym-bot/
├── src/main/java/com/mmagym/bot/
│   ├── MmaGymBotApplication.java
│   ├── config/          AppConfig.java
│   ├── controller/      WebhookController.java
│   ├── client/          WhatsAppClient.java
│   ├── service/
│   │   ├── CommandRouter.java
│   │   ├── AttendanceService.java
│   │   ├── MemberService.java
│   │   ├── AdminService.java
│   │   ├── SchedulerService.java
│   │   └── SessionService.java
│   ├── model/           Member, Attendance, Progress, GymClass
│   ├── repository/      4 JPA repositories
│   └── dto/             WebhookPayload.java
├── src/main/resources/
│   └── application.yml
├── sql/
│   └── schema.sql       Database schema + seeded class schedule
├── deploy/
│   ├── mmabot.service   systemd unit file
│   └── nginx.conf       Nginx reverse proxy config
├── .env.example
└── pom.xml
```

---

## Local Setup

### Prerequisites

- Java 17+
- Maven 3.x
- MySQL 8.x running locally
- A [Meta Developer account](https://developers.facebook.com/) with a WhatsApp Business app

### 1. Create the database

```bash
mysql -u root -p < sql/schema.sql
```

### 2. Configure environment

```bash
cp .env.example .env
```

Fill in your values:

```env
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/mmagym?useSSL=false&serverTimezone=Asia/Kolkata&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=mmagym_user
SPRING_DATASOURCE_PASSWORD=yourpassword

META_ACCESS_TOKEN=EAAxxxxxx...
META_PHONE_NUMBER_ID=123456789012345
META_VERIFY_TOKEN=any_secret_string_you_choose

ADMIN_PHONE=919876543210        # Full international format, no +
SERVER_PORT=8080
```

### 3. Build and run

```bash
mvn clean package -DskipTests
source .env
java -jar target/mmabot.jar
```

### 4. Expose locally for Meta webhook testing

```bash
ngrok http 8080
```

Set the ngrok HTTPS URL as your webhook in the Meta developer console:
`https://<ngrok-id>.ngrok.io/webhook`

---

## Bot Commands

### Member Commands

| Command | Description |
|---------|-------------|
| `/checkin` | Mark today's attendance (time-windowed) |
| `/mystats` | This month's attendance count and percentage |
| `/streak` | Current consecutive attendance streak |
| `/mypayment` | Plan type, expiry date, days remaining |
| `/myrank` | Belt rank and join date |
| `/schedule` | This week's class schedule |
| `/progress log [kg] [type] [value] [notes]` | Log a training session |
| `/progress view` | See your last 10 progress entries |
| `/help` | List all available commands |

### Admin Commands

| Command | Description |
|---------|-------------|
| `/addmember` | Register a new member (conversational flow) |
| `/users` | List all active members |
| `/user [name]` | Full profile for a member |
| `/attendance [name]` | This month's attendance record |
| `/missed` | Members absent 5+ consecutive days |
| `/expiring` | Plans expiring within 7 days |
| `/summary` | Active count, today's check-ins, expired plans |
| `/mark [name] P/A/L` | Override attendance (Present/Absent/Late) |
| `/promote [name] [belt]` | Update belt rank and notify member |
| `/deactivate [name]` | Set member to INACTIVE |
| `/capacity` | Today's check-in count vs class capacity |
| `/defaulters` | Members with unpaid or expired plans |

---

## Automated Jobs

| Job | Schedule | Recipients |
|-----|----------|-----------|
| Class reminder | 6:00 AM daily | All active paid members |
| Payment warning (7 days) | 9:00 AM daily | Member + Admin |
| Payment warning (1 day) | 9:00 AM daily | Member + Admin |
| Plan expired alert | 9:00 AM daily | Member |
| Missed class alert (5+ days) | 10:00 AM daily | Admin only |
| Daily check-in summary | 8:00 PM daily | Admin only |
| Monthly report | 8:00 AM, 1st of month | Admin only |

All jobs run in IST (`Asia/Kolkata`).

---

## Attendance Rules

Check-in is accepted only when **all** of the following are true:

1. Member exists in the database
2. Member status is `ACTIVE` and payment is `PAID`
3. Current time is within ±30 minutes of a scheduled class
4. Member has not already checked in today (enforced by DB unique constraint)

Admin `/mark` bypasses all rules.

---

## Deployment (Hetzner VPS)

### Server setup

```bash
apt update && apt install -y openjdk-17-jdk mysql-server nginx certbot python3-certbot-nginx
```

### SSL

```bash
certbot --nginx -d yourdomain.com
```

### Nginx

Copy `deploy/nginx.conf` to `/etc/nginx/sites-available/mmabot`, replace `yourdomain.com`, then:

```bash
ln -s /etc/nginx/sites-available/mmabot /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
```

### Deploy the jar

```bash
mvn clean package -DskipTests
scp target/mmabot.jar ubuntu@<server-ip>:/home/ubuntu/mmabot/
scp .env ubuntu@<server-ip>:/home/ubuntu/mmabot/
```

### systemd service

```bash
sudo cp deploy/mmabot.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable mmabot
sudo systemctl start mmabot
sudo journalctl -u mmabot -f    # tail logs
```

### Register the webhook

In your Meta app dashboard set:
- **Webhook URL:** `https://yourdomain.com/webhook`
- **Verify token:** must match `META_VERIFY_TOKEN` in your `.env`
- **Subscribe to:** `messages`

---

## Phased Build Plan

| Phase | Scope | Target |
|-------|-------|--------|
| Phase 1 | `/checkin`, `/mystats`, `/help`, `/schedule`, class reminders | Week 1–2 |
| Phase 2 | All admin commands, payment/missed alerts | Week 3 |
| Phase 3 | Streak, progress, belt promotion, QR check-in | Week 4–5 |
| Phase 4 | Cashfree payment integration, `/pay`, auto-confirm | Week 6+ |

> Do not move to Phase 2 until `/checkin` works reliably for one full week with real members.

---

## Security Notes

- `ADMIN_PHONE` is the sole admin identifier — keep it in `.env`, never hardcoded
- Webhook requests are validated with `X-Hub-Signature-256` (HMAC-SHA256)
- Never commit `.env` to Git — it is in `.gitignore`
- Restrict SSH access to your IP: `ufw allow from <your-ip> to any port 22`

---

## Database Backup

```bash
# Add to crontab on the server — daily at 2 AM
0 2 * * * mysqldump -u mmagym_user -p'password' mmagym > /home/ubuntu/backups/mmagym_$(date +\%F).sql
```
