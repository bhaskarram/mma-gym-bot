package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.Attendance;
import com.mmagym.bot.model.GymClass;
import com.mmagym.bot.model.Member;
import com.mmagym.bot.model.Progress;
import com.mmagym.bot.repository.AttendanceRepository;
import com.mmagym.bot.repository.GymClassRepository;
import com.mmagym.bot.repository.ProgressRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class MemberService {

    private static final String[] DAY_NAMES = {"", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    private final AttendanceRepository attendanceRepo;
    private final GymClassRepository   gymClassRepo;
    private final ProgressRepository   progressRepo;
    private final AttendanceService    attendanceService;
    private final WhatsAppClient       whatsApp;
    private final ZoneId               zoneId;

    public MemberService(AttendanceRepository attendanceRepo,
                         GymClassRepository gymClassRepo,
                         ProgressRepository progressRepo,
                         AttendanceService attendanceService,
                         WhatsAppClient whatsApp,
                         @Value("${app.timezone}") String timezone) {
        this.attendanceRepo    = attendanceRepo;
        this.gymClassRepo      = gymClassRepo;
        this.progressRepo      = progressRepo;
        this.attendanceService = attendanceService;
        this.whatsApp          = whatsApp;
        this.zoneId            = ZoneId.of(timezone);
    }

    public void myStats(String phone, Member member) {
        LocalDate now   = LocalDate.now(zoneId);
        long present    = attendanceRepo.countPresentInMonth(member, now.getYear(), now.getMonthValue());
        long daysInMonth = now.lengthOfMonth();
        long missed      = daysInMonth - present;
        int  pct         = (int) Math.round(present * 100.0 / Math.max(daysInMonth, 1));

        whatsApp.sendText(phone,
                "📊 Your stats for " + now.getMonth().name() + " " + now.getYear() + ":\n" +
                "✅ Present: " + present + " days\n" +
                "❌ Missed: " + missed + " days\n" +
                "📈 Attendance: " + pct + "%");
    }

    public void myStreak(String phone, Member member) {
        long streak = attendanceService.computeStreak(member);
        if (streak == 0) {
            whatsApp.sendText(phone, "No current streak. Check in today to start one! 💪");
        } else {
            whatsApp.sendText(phone, "🔥 Current streak: " + streak + " consecutive days!");
        }
    }

    public void myPayment(String phone, Member member) {
        LocalDate today  = LocalDate.now(zoneId);
        LocalDate expiry = member.getPlanExpiry();
        long daysLeft    = expiry == null ? 0 : today.until(expiry).getDays();

        whatsApp.sendText(phone,
                "💳 Plan: " + member.getPlanType() + "\n" +
                "📅 Expires: " + (expiry != null ? expiry.toString() : "N/A") + "\n" +
                "⏳ Days remaining: " + daysLeft + "\n" +
                "Status: " + member.getPaymentStatus());
    }

    public void myRank(String phone, Member member) {
        whatsApp.sendText(phone,
                "🥋 Belt: " + member.getBeltRank() + "\n" +
                "📅 Member since: " + (member.getJoinDate() != null ? member.getJoinDate().toString() : "N/A"));
    }

    public void schedule(String phone) {
        List<GymClass> classes = gymClassRepo.findByIsActiveTrueOrderByDayOfWeekAscClassTimeAsc();
        if (classes.isEmpty()) {
            whatsApp.sendText(phone, "No classes scheduled. Contact admin.");
            return;
        }
        StringBuilder sb = new StringBuilder("📅 *Weekly Schedule*\n\n");
        int lastDay = -1;
        for (GymClass gc : classes) {
            if (gc.getDayOfWeek() != lastDay) {
                sb.append("\n*").append(DAY_NAMES[gc.getDayOfWeek()]).append("*\n");
                lastDay = gc.getDayOfWeek();
            }
            sb.append("  ").append(gc.getClassTime()).append(" — ")
              .append(gc.getClassName());
            if (gc.getCoach() != null) sb.append(" (").append(gc.getCoach()).append(")");
            sb.append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    public void progress(String phone, Member member, String text) {
        String[] parts = text.split("\\s+", 3);
        if (parts.length < 2) {
            whatsApp.sendText(phone, "Usage:\n/progress log [weight] [type] [value] [notes]\n/progress view");
            return;
        }
        String sub = parts[1].toLowerCase();
        if ("view".equals(sub)) {
            progressView(phone, member);
        } else if ("log".equals(sub)) {
            progressLog(phone, member, text);
        } else {
            whatsApp.sendText(phone, "Usage: /progress log ... OR /progress view");
        }
    }

    private void progressLog(String phone, Member member, String text) {
        // /progress log [weight] [type] [value] [notes]
        String[] parts = text.split("\\s+", 6);
        if (parts.length < 5) {
            whatsApp.sendText(phone,
                    "Usage: /progress log [weight_kg] [type] [value] [notes]\n" +
                    "Example: /progress log 72.5 STRENGTH 10 Bench press reps");
            return;
        }
        try {
            Progress p = new Progress();
            p.setMember(member);
            p.setLogDate(LocalDate.now(zoneId));
            p.setWeightKg(new BigDecimal(parts[2]));
            p.setMetricType(parts[3].toUpperCase());
            p.setValue(new BigDecimal(parts[4]));
            if (parts.length > 5) p.setNotes(parts[5]);
            progressRepo.save(p);
            whatsApp.sendText(phone,
                    "Logged ✅\nWeight: " + p.getWeightKg() + " kg | " +
                    p.getMetricType() + ": " + p.getValue() +
                    (p.getNotes() != null ? "\n" + p.getNotes() : ""));
        } catch (NumberFormatException e) {
            whatsApp.sendText(phone, "Weight and value must be numbers. Example: /progress log 72.5 STRENGTH 10 notes");
        }
    }

    private void progressView(String phone, Member member) {
        List<Progress> entries = progressRepo.findTop10ByMemberOrderByLogDateDesc(member);
        if (entries.isEmpty()) {
            whatsApp.sendText(phone, "No progress entries yet. Use /progress log to add one.");
            return;
        }
        StringBuilder sb = new StringBuilder("📈 *Your last " + entries.size() + " entries*\n\n");
        for (Progress p : entries) {
            sb.append(p.getLogDate()).append(" | ")
              .append(p.getMetricType()).append(": ").append(p.getValue());
            if (p.getWeightKg() != null) sb.append(" | ").append(p.getWeightKg()).append(" kg");
            if (p.getNotes() != null)    sb.append("\n  ").append(p.getNotes());
            sb.append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    public void help(String phone) {
        whatsApp.sendText(phone,
                "🥊 *MMA Gym Bot Commands*\n\n" +
                "/checkin — Mark today's attendance\n" +
                "/mystats — This month's attendance stats\n" +
                "/streak — Your current attendance streak\n" +
                "/mypayment — Plan info and expiry date\n" +
                "/myrank — Belt rank and join date\n" +
                "/schedule — This week's class schedule\n" +
                "/progress log [kg] [type] [val] [notes] — Log a session\n" +
                "/progress view — See your last 10 entries\n" +
                "/help — Show this message");
    }
}
