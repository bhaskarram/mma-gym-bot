package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.Attendance;
import com.mmagym.bot.model.GymClass;
import com.mmagym.bot.model.Member;
import com.mmagym.bot.repository.AttendanceRepository;
import com.mmagym.bot.repository.GymClassRepository;
import com.mmagym.bot.repository.MemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final MemberRepository     memberRepo;
    private final AttendanceRepository attendanceRepo;
    private final GymClassRepository   gymClassRepo;
    private final WhatsAppClient       whatsApp;
    private final String               adminPhone;
    private final ZoneId               zoneId;

    public SchedulerService(MemberRepository memberRepo,
                            AttendanceRepository attendanceRepo,
                            GymClassRepository gymClassRepo,
                            WhatsAppClient whatsApp,
                            @Value("${app.admin-phone}") String adminPhone,
                            @Value("${app.timezone}")    String timezone) {
        this.memberRepo     = memberRepo;
        this.attendanceRepo = attendanceRepo;
        this.gymClassRepo   = gymClassRepo;
        this.whatsApp       = whatsApp;
        this.adminPhone     = adminPhone;
        this.zoneId         = ZoneId.of(timezone);
    }

    // ── 6 AM daily — one consolidated class reminder per member ───────────────
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void sendClassReminders() {
        LocalDate today  = LocalDate.now(zoneId);
        int isoDay       = today.getDayOfWeek().getValue();
        List<GymClass> classes = gymClassRepo.findByDayOfWeekAndIsActiveTrue(isoDay);
        if (classes.isEmpty()) return;

        // FIX: use findActivePaidMembers() — expired members don't get reminders
        List<Member> active = memberRepo.findActivePaidMembers();

        // FIX: one message per member (not one per class) — avoids Meta rate limits
        for (Member m : active) {
            StringBuilder sb = new StringBuilder("🥊 Hey " + m.getName() + "! Today's classes:\n\n");
            classes.stream()
                   .sorted((a, b) -> a.getClassTime().compareTo(b.getClassTime()))
                   .forEach(gc -> sb.append("  🕐 ")
                           .append(gc.getClassTime())
                           .append(" – ").append(gc.getClassEndTime())
                           .append("  ").append(gc.getClassName()).append("\n"));
            sb.append("\nSee you on the mat! 💪");
            whatsApp.sendText(m.getPhone(), sb.toString().trim());
        }
        log.info("Class reminders sent to {} members", active.size());
    }

    // ── 9 AM daily — payment expiry warnings in one pass ─────────────────────
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void sendPaymentWarnings() {
        LocalDate today = LocalDate.now(zoneId);

        for (Member m : memberRepo.findExpiringBetween(today.plusDays(7), today.plusDays(7))) {
            whatsApp.sendText(m.getPhone(),
                    "⏳ " + m.getName() + ", your plan expires on " + m.getPlanExpiry() +
                    " — 7 days left. Contact admin to renew.");
            whatsApp.sendText(adminPhone,
                    "[Renewal] " + m.getName() + " expires in 7 days (" + m.getPlanExpiry() + ")");
        }

        for (Member m : memberRepo.findExpiringBetween(today.plusDays(1), today.plusDays(1))) {
            whatsApp.sendText(m.getPhone(),
                    "🚨 Final reminder — your plan expires tomorrow. Please renew today.");
            whatsApp.sendText(adminPhone,
                    "[Urgent] " + m.getName() + " expires tomorrow");
        }

        // FIX: set both paymentStatus AND mark for exclusion from active queries
        for (Member m : memberRepo.findExpiringBetween(today, today)) {
            m.setPaymentStatus(Member.PaymentStatus.EXPIRED);
            memberRepo.save(m);
            whatsApp.sendText(m.getPhone(),
                    "❌ Your plan expired today. You won't be able to check in. Contact admin to renew 🙏");
            whatsApp.sendText(adminPhone,
                    "[Expired] " + m.getName() + "'s plan expired today");
        }
    }

    // ── 5:30 AM daily — birthday shoutouts ───────────────────────────────────
    @Scheduled(cron = "0 30 5 * * *", zone = "Asia/Kolkata")
    public void sendBirthdayShoutouts() {
        LocalDate today = LocalDate.now(zoneId);
        List<Member> birthdays = memberRepo.findByDobMonthAndDay(today.getMonthValue(), today.getDayOfMonth());
        if (birthdays.isEmpty()) return;

        List<Member> allActive = memberRepo.findActivePaidMembers();

        for (Member bday : birthdays) {
            whatsApp.sendText(bday.getPhone(),
                    "🎂 Happy Birthday, " + bday.getName() + "! " +
                    "Wishing you strength, speed, and another year of gains 🥊");

            String classmate = "🎂 Today is " + bday.getName() + "'s birthday! " +
                               "Wish them well when you see them at the gym 🙌";
            for (Member m : allActive) {
                if (!m.getPhone().equals(bday.getPhone())) {
                    whatsApp.sendText(m.getPhone(), classmate);
                }
            }

            whatsApp.sendText(adminPhone,
                    "🎂 Birthday today: " + bday.getName() +
                    " (joined " + bday.getJoinDate() + "). Consider a free class or gift.");
        }
        log.info("Birthday shoutouts sent for {} member(s)", birthdays.size());
    }

    // ── 10 AM daily — comeback nudge + admin alert for long absentees ─────────
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void sendMissedClassAlerts() {
        LocalDate today  = LocalDate.now(zoneId);
        LocalDate cutoff = today.minusDays(7);

        // FIX: use findActivePaidMembers — expired members don't get nudges
        List<Member> active = memberRepo.findActivePaidMembers();
        StringBuilder adminSb = new StringBuilder();

        // Collect all class days in the window (Mon–Sat)
        List<LocalDate> windowClassDays = cutoff.datesUntil(today)
                .filter(d -> d.getDayOfWeek().getValue() <= 6)
                .toList();

        for (Member m : active) {
            // FIX: bulk fetch all present dates in one query (not N+1 existsBy calls)
            Set<LocalDate> presentDays = new HashSet<>(
                    attendanceRepo.findPresentDatesBetween(m, cutoff, today));

            long classDaysMissed = windowClassDays.stream()
                    .filter(d -> !presentDays.contains(d))
                    .count();

            if (classDaysMissed >= 3) {
                LocalDate lastNudge = m.getLastNudgeDate();
                boolean alreadyNudged = lastNudge != null && lastNudge.isAfter(today.minusDays(7));
                if (!alreadyNudged) {
                    LocalDate tomorrow = today.plusDays(1);
                    int tmrDay = tomorrow.getDayOfWeek().getValue();
                    List<GymClass> tmrClasses = gymClassRepo.findByDayOfWeekAndIsActiveTrue(tmrDay);
                    String classLine = tmrClasses.isEmpty() ? "your next session"
                            : tmrClasses.get(0).getClassName() + " at " + tmrClasses.get(0).getClassTime();
                    whatsApp.sendText(m.getPhone(),
                            "Hey " + m.getName() + ", we haven't seen you in a few days 👊\n" +
                            "Tomorrow is " + classLine + " — come back and get on the mat!");
                    m.setLastNudgeDate(today);
                    memberRepo.save(m);
                }
            }

            if (classDaysMissed >= 5) {
                List<Attendance> all = attendanceRepo.findAllByMemberOrderByDateDesc(m);
                String lastSeen = all.stream()
                        .filter(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT)
                        .findFirst()
                        .map(a -> a.getClassDate().toString())
                        .orElse("never");
                adminSb.append("• ").append(m.getName()).append(" — last seen: ").append(lastSeen).append("\n");
            }
        }

        if (!adminSb.isEmpty()) {
            whatsApp.sendText(adminPhone,
                    "⚠️ Members absent 5+ consecutive class days:\n\n" + adminSb.toString().trim());
        }
    }

    // ── 9 PM last day of month — monthly leaderboard broadcast ───────────────
    @Scheduled(cron = "0 0 21 L * *", zone = "Asia/Kolkata")
    public void sendMonthlyLeaderboard() {
        LocalDate today = LocalDate.now(zoneId);
        String msg = buildLeaderboard(today.getYear(), today.getMonthValue(), today);
        if (msg == null) return;
        List<Member> allActive = memberRepo.findActivePaidMembers();
        for (Member m : allActive) whatsApp.sendText(m.getPhone(), msg);
        log.info("Monthly leaderboard sent to {} members", allActive.size());
    }

    /** Shared leaderboard builder — used by scheduler and admin on-demand. */
    public String buildLeaderboard(int year, int month, LocalDate today) {
        List<Object[]> top = attendanceRepo.findTopAttendersForMonth(year, month, 10);
        if (top.isEmpty()) return null;

        LocalDate first      = LocalDate.of(year, month, 1);
        LocalDate countUntil = (today.getYear() == year && today.getMonthValue() == month)
                               ? today : first.withDayOfMonth(first.lengthOfMonth());
        long totalClassDays  = first.datesUntil(countUntil.plusDays(1))
                .filter(d -> d.getDayOfWeek().getValue() <= 6)
                .count();

        String monthName = first.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        StringBuilder sb = new StringBuilder();
        sb.append("🏆 *").append(monthName).append(" ").append(year).append(" Leaderboard* 🏆\n\n");

        String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"};
        for (int i = 0; i < top.size(); i++) {
            Long memberId = ((Number) top.get(i)[0]).longValue();
            long count    = ((Number) top.get(i)[top.get(i).length - 1]).longValue();
            final int idx = i;
            final long c  = count;
            memberRepo.findById(memberId).ifPresent(m -> {
                int pct = totalClassDays == 0 ? 0 : (int) Math.round(c * 100.0 / totalClassDays);
                sb.append(medals[idx]).append(" *").append(m.getName()).append("*")
                  .append(" — ").append(c).append(" days (").append(pct).append("%)\n");
            });
        }
        sb.append("\n💪 Keep showing up — consistency is the secret!");
        return sb.toString().trim();
    }

    // ── 8 PM daily — check-in summary to admin ───────────────────────────────
    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Kolkata")
    public void sendDailySummary() {
        LocalDate today  = LocalDate.now(zoneId);
        long active      = memberRepo.findActivePaidMembers().size();
        long checkins    = attendanceRepo.countPresentOnDate(today);
        whatsApp.sendText(adminPhone,
                "📊 Daily Summary — " + today + "\n" +
                "✅ Check-ins: " + checkins + " / " + active + " active members");
    }

    // ── 8 AM on 1st of month — monthly report to admin ───────────────────────
    @Scheduled(cron = "0 0 8 1 * *", zone = "Asia/Kolkata")
    public void sendMonthlyReport() {
        LocalDate today     = LocalDate.now(zoneId);
        // FIX: use lastMonth for both name AND year (fixes January bug)
        LocalDate lastMonth = today.minusMonths(1);
        String monthName    = lastMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        List<Member> active = memberRepo.findActivePaidMembers();
        long expired        = memberRepo.findDefaulters().size();

        long totalPresent = 0;
        for (Member m : active) {
            totalPresent += attendanceRepo.countPresentInMonth(
                    m, lastMonth.getYear(), lastMonth.getMonthValue());
        }
        int avgPct = active.isEmpty() ? 0
                : (int) Math.round(totalPresent * 100.0 / (active.size() * 26.0)); // 26 class days/month avg

        whatsApp.sendText(adminPhone,
                "📅 *Monthly Report — " + monthName + " " + lastMonth.getYear() + "*\n\n" +
                "👥 Active members: " + active.size() + "\n" +
                "📊 Avg attendance: " + avgPct + "%\n" +
                "⚠️  Expired/defaulters: " + expired);
    }
}
