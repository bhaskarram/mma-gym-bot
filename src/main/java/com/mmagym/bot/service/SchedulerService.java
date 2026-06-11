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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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

    // 6 AM daily — class reminder to all active members
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void sendClassReminders() {
        LocalDate today = LocalDate.now(zoneId);
        int isoDay = today.getDayOfWeek().getValue();
        List<GymClass> classes = gymClassRepo.findByDayOfWeekAndIsActiveTrue(isoDay);
        if (classes.isEmpty()) return;

        List<Member> active = memberRepo.findByStatus(Member.MemberStatus.ACTIVE);
        for (Member m : active) {
            if (m.getPaymentStatus() != Member.PaymentStatus.PAID) continue;
            for (GymClass gc : classes) {
                whatsApp.sendText(m.getPhone(),
                        "Hey " + m.getName() + "! " + gc.getClassName() +
                        " starts at " + gc.getClassTime() + " today. See you on the mat 🥊");
            }
        }
        log.info("Class reminders sent to {} active members", active.size());
    }

    // 9 AM daily — payment expiry warnings (7-day, 1-day, expired) in one pass
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    public void sendPaymentWarnings() {
        LocalDate today = LocalDate.now(zoneId);
        List<Member> expiring7 = memberRepo.findExpiringBetween(today.plusDays(7), today.plusDays(7));
        List<Member> expiring1 = memberRepo.findExpiringBetween(today.plusDays(1), today.plusDays(1));
        List<Member> expiredToday = memberRepo.findExpiringBetween(today, today);

        for (Member m : expiring7) {
            String msg = m.getName() + ", your plan expires on " + m.getPlanExpiry() +
                         " — 7 days left. Contact admin to renew.";
            whatsApp.sendText(m.getPhone(), msg);
            whatsApp.sendText(adminPhone, "[Renewal Reminder] " + msg);
        }
        for (Member m : expiring1) {
            String msg = "Final reminder — " + m.getName() + "'s plan expires tomorrow. Please renew today.";
            whatsApp.sendText(m.getPhone(), msg);
            whatsApp.sendText(adminPhone, "[Renewal Reminder] " + msg);
        }
        for (Member m : expiredToday) {
            m.setPaymentStatus(Member.PaymentStatus.EXPIRED);
            memberRepo.save(m);
            whatsApp.sendText(m.getPhone(),
                    "Your plan expired today. You won't be able to check in. Contact admin to renew.");
        }
    }

    // 10 AM daily — alert admin about members missing 5+ consecutive days
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Kolkata")
    public void sendMissedClassAlerts() {
        LocalDate today  = LocalDate.now(zoneId);
        LocalDate cutoff = today.minusDays(5);
        List<Member> active = memberRepo.findByStatus(Member.MemberStatus.ACTIVE);
        StringBuilder sb = new StringBuilder();

        for (Member m : active) {
            List<Attendance> recent = attendanceRepo
                    .findByMemberAndClassDateBetweenOrderByClassDateAsc(m, cutoff, today);
            boolean anyPresent = recent.stream()
                    .anyMatch(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT);
            if (!anyPresent) {
                List<Attendance> all = attendanceRepo.findAllByMemberOrderByDateDesc(m);
                String lastSeen = all.stream()
                        .filter(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT)
                        .findFirst()
                        .map(a -> a.getClassDate().toString())
                        .orElse("never");
                sb.append("• ").append(m.getName()).append(" — last seen: ").append(lastSeen).append("\n");
            }
        }
        if (!sb.isEmpty()) {
            whatsApp.sendText(adminPhone,
                    "⚠️ Members absent 5+ consecutive days:\n\n" + sb.toString().trim());
        }
    }

    // 8 PM daily — check-in summary to admin
    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Kolkata")
    public void sendDailySummary() {
        LocalDate today = LocalDate.now(zoneId);
        long active   = memberRepo.findByStatus(Member.MemberStatus.ACTIVE).size();
        long checkins = attendanceRepo.countPresentOnDate(today);
        whatsApp.sendText(adminPhone,
                "📊 Daily Summary — " + today + "\n" +
                "✅ Check-ins: " + checkins + " / " + active + " active members");
    }

    // 8 AM on the 1st of each month — monthly report to admin
    @Scheduled(cron = "0 0 8 1 * *", zone = "Asia/Kolkata")
    public void sendMonthlyReport() {
        LocalDate today = LocalDate.now(zoneId);
        List<Member> active = memberRepo.findByStatus(Member.MemberStatus.ACTIVE);
        long expired = memberRepo.findDefaulters().size();

        long totalPresent = 0;
        for (Member m : active) {
            totalPresent += attendanceRepo.countPresentInMonth(
                    m, today.minusMonths(1).getYear(), today.minusMonths(1).getMonthValue());
        }
        int avgPct = active.isEmpty() ? 0
                : (int) Math.round(totalPresent * 100.0 / (active.size() * 30.0));

        whatsApp.sendText(adminPhone,
                "📅 Monthly Report — " + today.getMonth().minus(1) + " " + today.getYear() + "\n\n" +
                "👥 Active members: " + active.size() + "\n" +
                "📊 Avg attendance: " + avgPct + "%\n" +
                "⚠️  Expired/defaulters: " + expired);
    }
}
