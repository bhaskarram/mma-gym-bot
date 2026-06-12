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

    // index 0 unused; ISO weekday: 1=Mon … 7=Sun
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
        LocalDate today   = LocalDate.now(zoneId);
        LocalDate firstDay = today.withDayOfMonth(1);
        LocalDate lastDay  = today.withDayOfMonth(today.lengthOfMonth());

        // Fetch all attendance records for this month
        List<Attendance> records = attendanceRepo
                .findByMemberAndClassDateBetweenOrderByClassDateAsc(member, firstDay, lastDay);

        // Build a map: date → status
        java.util.Map<LocalDate, Attendance.AttendanceStatus> statusMap = new java.util.LinkedHashMap<>();
        for (Attendance a : records) {
            statusMap.put(a.getClassDate(), a.getStatus());
        }

        // Counters
        int present = 0, absent = 0, late = 0;

        // Build calendar: week rows (Mon–Sun), Sun = rest
        String monthName = today.getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
        StringBuilder sb = new StringBuilder();
        sb.append("📅 *").append(member.getName()).append(" — ").append(monthName).append(" ").append(today.getYear()).append("*\n\n");
        sb.append("Mo Tu We Th Fr Sa  Su\n");

        // Start at first day of month; pad empty cells before
        java.time.DayOfWeek firstDow = firstDay.getDayOfWeek(); // MON=1 … SUN=7
        int startPad = firstDow.getValue() - 1; // 0-based padding before day 1

        int col = 0;
        // Leading spaces
        for (int i = 0; i < startPad; i++) {
            sb.append("   ");
            col++;
            if (col == 6) { sb.append("  "); } // extra gap before Sunday column
        }

        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            boolean isSunday = d.getDayOfWeek().getValue() == 7;
            boolean isFuture = d.isAfter(today);

            String cell;
            if (isSunday) {
                cell = "🌙"; // rest day
            } else if (isFuture) {
                cell = "⬜"; // not yet
            } else {
                Attendance.AttendanceStatus st = statusMap.get(d);
                if (st == null) {
                    // class day in the past with no record = absent
                    cell = "❌";
                    absent++;
                } else {
                    switch (st) {
                        case PRESENT -> { cell = "✅"; present++; }
                        case LATE    -> { cell = "🟡"; late++; }
                        default      -> { cell = "❌"; absent++; }
                    }
                }
            }

            // Add gap before Sunday column for readability
            if (col == 6) sb.append(" ");
            sb.append(cell);
            col++;

            if (col == 7) {
                sb.append("\n");
                col = 0;
            } else {
                sb.append(" ");
            }
        }

        // Summary line
        int classDays = present + absent + late;
        int pct = classDays == 0 ? 0 : (int) Math.round(present * 100.0 / classDays);

        sb.append("\n");
        sb.append("✅ Present: ").append(present)
          .append("  🟡 Late: ").append(late)
          .append("  ❌ Absent: ").append(absent).append("\n");
        sb.append("📈 Attendance: ").append(pct).append("% this month");
        if (member.getStreakCount() > 0) {
            sb.append("\n🔥 Streak: ").append(member.getStreakCount()).append(" days");
        }

        whatsApp.sendText(phone, sb.toString().trim());
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

    public void schedule(String phone, String text) {
        String[] parts = text.trim().split("\\s+");
        String arg = parts.length > 1 ? parts[1].toLowerCase() : "";

        if ("week".equals(arg) || "all".equals(arg)) {
            scheduleWeek(phone);
        } else {
            scheduleToday(phone);
        }
    }

    private void scheduleToday(String phone) {
        LocalDate today  = LocalDate.now(zoneId);
        int isoDay       = today.getDayOfWeek().getValue(); // 1=Mon … 7=Sun
        String dayName   = DAY_NAMES[isoDay];

        List<GymClass> classes = gymClassRepo.findByDayOfWeekAndIsActiveTrue(isoDay);

        if (classes.isEmpty()) {
            whatsApp.sendText(phone,
                "📅 No classes today (" + dayName + "). Rest day — see you tomorrow! 💪\n\nFull week: /schedule week");
            return;
        }

        StringBuilder sb = new StringBuilder("📅 *Today's Classes — " + dayName + "*\n\n");
        classes.stream()
               .sorted((a, b) -> a.getClassTime().compareTo(b.getClassTime()))
               .forEach(gc -> {
                   sb.append("🕐 ").append(gc.getClassTime())
                     .append(" – ").append(gc.getClassEndTime())
                     .append("  |  ").append(gc.getClassName());
                   if (gc.getCoach() != null) sb.append(" (").append(gc.getCoach()).append(")");
                   sb.append("\n");
               });
        sb.append("\nFull week: /schedule week");
        whatsApp.sendText(phone, sb.toString().trim());
    }

    private void scheduleWeek(String phone) {
        List<GymClass> classes = gymClassRepo.findByIsActiveTrueOrderByDayOfWeekAscClassTimeAsc();
        if (classes.isEmpty()) {
            whatsApp.sendText(phone, "No classes scheduled. Contact admin.");
            return;
        }
        StringBuilder sb = new StringBuilder("📅 *Weekly Schedule*\n");
        int lastDay = -1;
        for (GymClass gc : classes) {
            if (gc.getDayOfWeek() != lastDay) {
                sb.append("\n*").append(DAY_NAMES[gc.getDayOfWeek()]).append("*\n");
                lastDay = gc.getDayOfWeek();
            }
            sb.append("  ").append(gc.getClassTime())
              .append(" – ").append(gc.getClassEndTime())
              .append("  ").append(gc.getClassName());
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
                "/streak — Your current streak + shield status\n" +
                "/mypayment — Plan info and expiry date\n" +
                "/schedule — Today's classes\n" +
                "/schedule week — Full weekly timetable\n" +
                "/weight [kg] — Log your weight\n" +
                "/weight history — See last 5 weight entries\n" +
                "/progress log [kg] [type] [val] [notes] — Log a training metric\n" +
                "/progress view — See your last 10 entries\n" +
                "/help — Show this message");
    }
}
