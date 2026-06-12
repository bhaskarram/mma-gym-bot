package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.*;
import com.mmagym.bot.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {

    private static final DateTimeFormatter DOB_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MemberRepository     memberRepo;
    private final AttendanceRepository attendanceRepo;
    private final GymClassRepository   gymClassRepo;
    private final AttendanceService    attendanceService;
    private final SessionService       sessionService;
    private final SchedulerService     schedulerService;
    private final WhatsAppClient       whatsApp;
    private final ZoneId               zoneId;

    public AdminService(MemberRepository memberRepo,
                        AttendanceRepository attendanceRepo,
                        GymClassRepository gymClassRepo,
                        AttendanceService attendanceService,
                        SessionService sessionService,
                        SchedulerService schedulerService,
                        WhatsAppClient whatsApp,
                        @Value("${app.timezone}") String timezone) {
        this.memberRepo        = memberRepo;
        this.attendanceRepo    = attendanceRepo;
        this.gymClassRepo      = gymClassRepo;
        this.attendanceService = attendanceService;
        this.sessionService    = sessionService;
        this.schedulerService  = schedulerService;
        this.whatsApp          = whatsApp;
        this.zoneId            = ZoneId.of(timezone);
    }

    // ─── /users ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void listUsers(String phone) {
        List<Member> members = memberRepo.findByStatus(Member.MemberStatus.ACTIVE);
        if (members.isEmpty()) { whatsApp.sendText(phone, "No active members."); return; }
        StringBuilder sb = new StringBuilder("👥 *Active Members (" + members.size() + ")*\n\n");
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            sb.append(i + 1).append(". ").append(m.getName())
              .append(" | ").append(m.getPlanType())
              .append(" | Exp: ").append(m.getPlanExpiry())
              .append(" | ").append(m.getPaymentStatus())
              .append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ─── /user [name or phone] ────────────────────────────────────────────────

    public void getUser(String phone, String term) {
        if (term.isBlank()) { whatsApp.sendText(phone, "Usage: /user [name or phone]"); return; }
        List<Member> results = memberRepo.searchByNameOrPhone(term);
        if (results.isEmpty()) { whatsApp.sendText(phone, "No member found for: " + term); return; }
        Member m = results.get(0);
        LocalDate today = LocalDate.now(zoneId);
        long monthAttendance = attendanceRepo.countPresentInMonth(m, today.getYear(), today.getMonthValue());
        whatsApp.sendText(phone,
                "👤 *" + m.getName() + "*\n" +
                "📱 " + m.getPhone() + "\n" +
                "🎂 DOB: " + (m.getDob() != null ? m.getDob().format(DOB_FMT) : "N/A") + "\n" +
                "⚖️ Weight: " + (m.getWeightKg() != null ? m.getWeightKg() + " kg" : "N/A") + "\n" +
                "📦 Plan: " + m.getPlanType() + " | Exp: " + m.getPlanExpiry() + "\n" +
                "💳 Payment: " + m.getPaymentStatus() + "\n" +
                "📅 Status: " + m.getStatus() + "\n" +
                "📊 This month: " + monthAttendance + " days\n" +
                (m.getInjuries() != null ? "🩹 Injuries: " + m.getInjuries() + "\n" : ""));
    }

    // ─── /attendance [name] ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void memberAttendance(String phone, String term) {
        if (term.isBlank()) { whatsApp.sendText(phone, "Usage: /attendance [name or phone]"); return; }
        List<Member> results = memberRepo.searchByNameOrPhone(term);
        if (results.isEmpty()) { whatsApp.sendText(phone, "No member found for: " + term); return; }
        Member m = results.get(0);
        LocalDate today    = LocalDate.now(zoneId);
        LocalDate firstDay = today.withDayOfMonth(1);
        LocalDate lastDay  = today.withDayOfMonth(today.lengthOfMonth());

        List<Attendance> records = attendanceRepo
                .findByMemberAndClassDateBetweenOrderByClassDateAsc(m, firstDay, lastDay);

        // Build date → status map
        Map<LocalDate, Attendance.AttendanceStatus> statusMap = new HashMap<>();
        for (Attendance a : records) statusMap.put(a.getClassDate(), a.getStatus());

        int present = 0, absent = 0, late = 0;

        String monthName = today.getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);

        StringBuilder sb = new StringBuilder();
        sb.append("📋 *").append(m.getName()).append(" — ").append(monthName).append(" ").append(today.getYear()).append("*\n");
        sb.append("📱 ").append(m.getPhone()).append("  |  💳 ").append(m.getPlanType()).append("\n\n");
        sb.append("Mo Tu We Th Fr Sa  Su\n");

        java.time.DayOfWeek firstDow = firstDay.getDayOfWeek();
        int startPad = firstDow.getValue() - 1;
        int col = 0;

        for (int i = 0; i < startPad; i++) {
            sb.append("   ");
            col++;
            if (col == 6) sb.append("  ");
        }

        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            boolean isSunday = d.getDayOfWeek().getValue() == 7;
            boolean isFuture = d.isAfter(today);

            String cell;
            if (isSunday) {
                cell = "🌙";
            } else if (isFuture) {
                cell = "⬜";
            } else {
                Attendance.AttendanceStatus st = statusMap.get(d);
                if (st == null) {
                    cell = "❌"; absent++;
                } else switch (st) {
                    case PRESENT -> { cell = "✅"; present++; }
                    case LATE    -> { cell = "🟡"; late++; }
                    default      -> { cell = "❌"; absent++; }
                }
            }

            if (col == 6) sb.append(" ");
            sb.append(cell);
            col++;

            if (col == 7) { sb.append("\n"); col = 0; }
            else sb.append(" ");
        }

        int classDays = present + absent + late;
        int pct = classDays == 0 ? 0 : (int) Math.round(present * 100.0 / classDays);

        sb.append("\n");
        sb.append("✅ Present: ").append(present)
          .append("  🟡 Late: ").append(late)
          .append("  ❌ Absent: ").append(absent).append("\n");
        sb.append("📈 Attendance: ").append(pct).append("%");
        if (m.getStreakCount() > 0) sb.append("  🔥 Streak: ").append(m.getStreakCount());
        if (m.getPlanExpiry() != null) {
            long days = today.until(m.getPlanExpiry()).getDays();
            sb.append("\n⏳ Expires: ").append(m.getPlanExpiry()).append(" (").append(days).append("d left)");
        }

        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ─── /missed ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void missedMembers(String phone) {
        LocalDate today  = LocalDate.now(zoneId);
        LocalDate cutoff = today.minusDays(5);
        List<Member> active = memberRepo.findByStatus(Member.MemberStatus.ACTIVE);
        StringBuilder sb = new StringBuilder("⚠️ *Members absent 5+ consecutive days*\n\n");
        int count = 0;
        for (Member m : active) {
            List<Attendance> recent = attendanceRepo
                    .findByMemberAndClassDateBetweenOrderByClassDateAsc(m, cutoff, today);
            boolean anyPresent = recent.stream()
                    .anyMatch(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT);
            if (!anyPresent) {
                List<Attendance> all = attendanceRepo.findAllByMemberOrderByDateDesc(m);
                String lastSeen = all.stream()
                        .filter(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT)
                        .findFirst().map(a -> a.getClassDate().toString()).orElse("never");
                count++;
                sb.append(count).append(". ").append(m.getName())
                  .append(" — last seen ").append(lastSeen).append("\n");
            }
        }
        if (count == 0) whatsApp.sendText(phone, "All members attended in the last 5 days ✅");
        else { sb.append("\nSend /mark [name] P to override."); whatsApp.sendText(phone, sb.toString().trim()); }
    }

    // ─── /expiring ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void expiringMembers(String phone) {
        LocalDate today = LocalDate.now(zoneId);
        List<Member> expiring = memberRepo.findExpiringBetween(today, today.plusDays(7));
        if (expiring.isEmpty()) { whatsApp.sendText(phone, "No plans expiring in the next 7 days ✅"); return; }
        StringBuilder sb = new StringBuilder("⏰ *Expiring in 7 days (" + expiring.size() + ")*\n\n");
        for (Member m : expiring) sb.append("• ").append(m.getName()).append(" — expires ").append(m.getPlanExpiry()).append("\n");
        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ─── /summary ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void summary(String phone) {
        List<Member> all   = memberRepo.findAll();
        long active        = all.stream().filter(m -> m.getStatus() == Member.MemberStatus.ACTIVE).count();
        long expired       = all.stream().filter(m -> m.getPaymentStatus() == Member.PaymentStatus.EXPIRED).count();
        LocalDate today    = LocalDate.now(zoneId);
        long todayCheckins = attendanceRepo.countPresentOnDate(today);
        whatsApp.sendText(phone,
                "📊 *Gym Summary*\n\n" +
                "👥 Active members: " + active + "\n" +
                "⚠️  Expired plans: " + expired + "\n" +
                "✅ Today's check-ins: " + todayCheckins);
    }

    // ─── /mark [name] [P/A/L] ────────────────────────────────────────────────

    public void markAttendance(String phone, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) { whatsApp.sendText(phone, "Usage: /mark [name] [P/A/L]  (flag optional, default A)"); return; }

        // Detect if last token is a P/A/L flag
        String lastToken = parts[parts.length - 1].toUpperCase();
        boolean hasFlag  = lastToken.equals("P") || lastToken.equals("A") || lastToken.equals("L");

        String flag = hasFlag ? lastToken : "A";
        String name = hasFlag
                ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length - 1))
                : String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));

        if (name.isBlank()) { whatsApp.sendText(phone, "Usage: /mark [name] [P/A/L]  (flag optional, default A)"); return; }
        Attendance.AttendanceStatus status = switch (flag) {
            case "P" -> Attendance.AttendanceStatus.PRESENT;
            case "L" -> Attendance.AttendanceStatus.LATE;
            default  -> Attendance.AttendanceStatus.ABSENT;
        };
        List<Member> results = memberRepo.searchByNameOrPhone(name);
        if (results.isEmpty()) { whatsApp.sendText(phone, "No member found: " + name); return; }
        Member m = results.get(0);
        attendanceService.adminMarkAttendance(phone, m, LocalDate.now(zoneId), status);
        whatsApp.sendText(phone, "Marked " + m.getName() + " as " + status + " for today ✅");
    }

    // ─── /addmember (multi-step) ──────────────────────────────────────────────

    public void startAddMember(String phone) {
        sessionService.put(phone, "ADD_MEMBER_NAME");
        whatsApp.sendText(phone, "Enter the new member's full name:");
    }

    public void continueSession(String phone, String text) {
        SessionService.Session session = sessionService.get(phone);
        if (session == null) return;

        switch (session.action()) {
            case "ADD_MEMBER_NAME" -> {
                Map<String, String> d1 = new HashMap<>();
                d1.put("name", text.trim());
                sessionService.put(phone, "ADD_MEMBER_PHONE", d1);
                whatsApp.sendText(phone, "Enter phone number (with country code, e.g. 919876543210):");
            }
            case "ADD_MEMBER_PHONE" -> {
                String cleaned = text.replaceAll("\\D", "");
                if (cleaned.length() < 10 || cleaned.length() > 15) {
                    whatsApp.sendText(phone, "Enter a valid phone with country code (10–15 digits, no spaces/symbols):");
                    return;
                }
                Map<String, String> d2 = new HashMap<>(session.data());
                d2.put("memberPhone", cleaned);
                sessionService.put(phone, "ADD_MEMBER_DOB", d2);
                whatsApp.sendText(phone, "Enter date of birth (DD/MM/YYYY):");
            }
            case "ADD_MEMBER_DOB" -> {
                LocalDate dob;
                try {
                    dob = LocalDate.parse(text.trim(), DOB_FMT);
                } catch (DateTimeParseException e) {
                    whatsApp.sendText(phone, "Invalid date. Use DD/MM/YYYY (e.g. 15/08/1995):");
                    return;
                }
                LocalDate today0 = LocalDate.now(zoneId);
                if (dob.isAfter(today0.minusYears(5)) || dob.isBefore(today0.minusYears(100))) {
                    whatsApp.sendText(phone, "DOB looks invalid. Enter DD/MM/YYYY:");
                    return;
                }
                Map<String, String> d3 = new HashMap<>(session.data());
                d3.put("dob", text.trim());
                sessionService.put(phone, "ADD_MEMBER_WEIGHT", d3);
                whatsApp.sendText(phone, "Enter weight in kg (e.g. 72.5):");
            }
            case "ADD_MEMBER_WEIGHT" -> {
                BigDecimal weight;
                try {
                    weight = new BigDecimal(text.trim());
                } catch (NumberFormatException e) {
                    whatsApp.sendText(phone, "Enter a valid weight in kg (e.g. 72.5):");
                    return;
                }
                if (weight.compareTo(BigDecimal.ONE) < 0 || weight.compareTo(new BigDecimal("300")) > 0) {
                    whatsApp.sendText(phone, "Weight must be between 1 and 300 kg:");
                    return;
                }
                Map<String, String> d4 = new HashMap<>(session.data());
                d4.put("weight", text.trim());
                sessionService.put(phone, "ADD_MEMBER_PLAN", d4);
                whatsApp.sendPlanPicker(phone);
            }
            case "ADD_MEMBER_PLAN" -> {
                String t = text.trim().toUpperCase();
                Member.PlanType planType = switch (t) {
                    case "PLAN_MONTHLY", "MONTHLY", "1"   -> Member.PlanType.MONTHLY;
                    case "PLAN_QUARTERLY", "QUARTERLY", "2" -> Member.PlanType.QUARTERLY;
                    case "PLAN_ANNUAL", "ANNUAL", "3"     -> Member.PlanType.ANNUAL;
                    default -> null;
                };
                if (planType == null) { whatsApp.sendPlanPicker(phone); return; }

                String name        = session.data().get("name");
                String memberPhone = session.data().get("memberPhone");

                if (memberRepo.findByPhone(memberPhone).isPresent()) {
                    sessionService.clear(phone);
                    whatsApp.sendText(phone, "❌ Phone " + memberPhone + " is already registered.");
                    return;
                }

                LocalDate today  = LocalDate.now(zoneId);
                LocalDate expiry = planType.calculateExpiry(today);

                Member m = new Member();
                m.setName(name);
                m.setPhone(memberPhone);
                m.setDob(LocalDate.parse(session.data().get("dob"), DOB_FMT));
                m.setWeightKg(new BigDecimal(session.data().get("weight")));
                m.setPlanType(planType);
                m.setJoinDate(today);
                m.setPlanExpiry(expiry);
                m.setPaymentStatus(Member.PaymentStatus.PAID);
                m.setStatus(Member.MemberStatus.ACTIVE);
                memberRepo.save(m);

                sessionService.clear(phone);
                whatsApp.sendText(phone,
                        "✅ *Member Registered!*\n\n" +
                        "👤 Name: " + name + "\n" +
                        "📱 Phone: " + memberPhone + "\n" +
                        "💳 Plan: " + planType + "\n" +
                        "📅 Expires: " + expiry);

                // Welcome message to the new member
                whatsApp.sendText(memberPhone,
                        "🥊 *Welcome to the Gym, " + name + "!*\n\n" +
                        "You're now officially a member. Here's what you can do:\n\n" +
                        "✅ Check in daily after class\n" +
                        "📊 Track your attendance & streak\n" +
                        "⚖️ Log your weight & progress\n" +
                        "📅 View today's class schedule\n\n" +
                        "💳 *Plan:* " + planType + "\n" +
                        "📅 *Expires:* " + expiry + "\n\n" +
                        "Just say *hi* to get started! 💪");
            }
            case "MARK_NAME" -> {
                // text is the member name/phone
                List<Member> results = memberRepo.searchByNameOrPhone(text.trim());
                if (results.isEmpty()) {
                    sessionService.clear(phone);
                    whatsApp.sendText(phone, "No member found: " + text.trim() + ". Try again from the menu.");
                    return;
                }
                Map<String, String> d = new HashMap<>();
                d.put("memberPhone", results.get(0).getPhone());
                d.put("memberName",  results.get(0).getName());
                sessionService.put(phone, "MARK_FLAG", d);
                whatsApp.sendText(phone,
                    "Member: *" + results.get(0).getName() + "*\nEnter status: P (Present), A (Absent), L (Late)");
            }
            case "MARK_FLAG" -> {
                String flag = text.trim().toUpperCase();
                Attendance.AttendanceStatus status = switch (flag) {
                    case "P" -> Attendance.AttendanceStatus.PRESENT;
                    case "L" -> Attendance.AttendanceStatus.LATE;
                    case "A" -> Attendance.AttendanceStatus.ABSENT;
                    default  -> null;
                };
                if (status == null) {
                    whatsApp.sendText(phone, "Please reply P, A, or L.");
                    return;
                }
                String mPhone = session.data().get("memberPhone");
                String mName  = session.data().get("memberName");
                Member m = memberRepo.findByPhone(mPhone).orElse(null);
                if (m != null) {
                    attendanceService.adminMarkAttendance(phone, m, LocalDate.now(zoneId), status);
                }
                sessionService.clear(phone);
                whatsApp.sendText(phone, "Marked *" + mName + "* as " + status + " for today ✅");
            }
            case "ATTENDANCE_NAME" -> {
                List<Member> results = memberRepo.searchByNameOrPhone(text.trim());
                if (results.isEmpty()) {
                    sessionService.clear(phone);
                    whatsApp.sendText(phone, "No member found: " + text.trim() + ". Try again from the menu.");
                    return;
                }
                sessionService.clear(phone);
                memberAttendance(phone, results.get(0).getPhone());
            }

            // ── Renewal steps ────────────────────────────────────────────────
            case "RENEW_NAME" -> {
                List<Member> results = memberRepo.searchByNameOrPhone(text.trim());
                if (results.isEmpty()) {
                    sessionService.clear(phone);
                    whatsApp.sendText(phone, "No member found: " + text.trim() + ". Try again from the menu.");
                    return;
                }
                Member m = results.get(0);
                LocalDate today  = LocalDate.now(zoneId);
                LocalDate expiry = m.getPlanExpiry();
                long daysLeft    = expiry != null ? today.until(expiry).getDays() : 0;

                Map<String, String> d = new HashMap<>();
                d.put("memberPhone", m.getPhone());
                d.put("memberName",  m.getName());
                sessionService.put(phone, "RENEW_PLAN", d);

                whatsApp.sendText(phone,
                        "👤 *" + m.getName() + "*\n" +
                        "💳 Current plan: " + m.getPlanType() + "\n" +
                        "📅 Expires: " + (expiry != null ? expiry : "N/A") +
                        (daysLeft < 0 ? " ⚠️ EXPIRED" : " (" + daysLeft + "d left)") + "\n\n" +
                        "Select new plan:");
                whatsApp.sendPlanPicker(phone);
            }
            case "RENEW_PLAN" -> {
                String t = text.trim().toUpperCase();
                Member.PlanType planType = switch (t) {
                    case "PLAN_MONTHLY",   "MONTHLY",   "1" -> Member.PlanType.MONTHLY;
                    case "PLAN_QUARTERLY", "QUARTERLY", "2" -> Member.PlanType.QUARTERLY;
                    case "PLAN_ANNUAL",    "ANNUAL",    "3" -> Member.PlanType.ANNUAL;
                    default -> null;
                };
                if (planType == null) { whatsApp.sendPlanPicker(phone); return; }

                String mPhone = session.data().get("memberPhone");
                String mName  = session.data().get("memberName");

                // Always clear session first — prevents stuck session on any failure path
                sessionService.clear(phone);

                Member m = memberRepo.findByPhone(mPhone).orElse(null);
                if (m == null) {
                    whatsApp.sendText(phone, "❌ Member no longer found. Please try again from the menu.");
                    return;
                }

                LocalDate today  = LocalDate.now(zoneId);
                LocalDate base   = (m.getPlanExpiry() != null && m.getPlanExpiry().isAfter(today))
                                   ? m.getPlanExpiry() : today;
                LocalDate newExpiry = planType.calculateExpiry(base);

                m.setPlanType(planType);
                m.setPlanExpiry(newExpiry);
                m.setPaymentStatus(Member.PaymentStatus.PAID);
                memberRepo.save(m);

                whatsApp.sendText(phone,
                        "✅ *Plan Renewed!*\n\n" +
                        "👤 " + mName + "\n" +
                        "💳 Plan: " + planType + "\n" +
                        "📅 New expiry: " + newExpiry);

                whatsApp.sendText(mPhone,
                        "🎉 Your membership has been renewed!\n\n" +
                        "💳 Plan: " + planType + "\n" +
                        "📅 Valid until: " + newExpiry + "\n\n" +
                        "Keep training hard — see you on the mat! 🥊");
            }

            default -> sessionService.clear(phone);
        }
    }

    // ─── Renewal flow ─────────────────────────────────────────────────────────

    public void startRenewPrompt(String phone) {
        sessionService.put(phone, "RENEW_NAME");
        whatsApp.sendText(phone, "Enter member name or phone number to renew:");
    }

    // ─── /mark prompt ─────────────────────────────────────────────────────────

    public void startMarkPrompt(String phone) {
        sessionService.put(phone, "MARK_NAME");
        whatsApp.sendText(phone, "Enter the member's name or phone number:");
    }

    public void startAttendancePrompt(String phone) {
        sessionService.put(phone, "ATTENDANCE_NAME");
        whatsApp.sendText(phone, "Enter the member's name or phone number:");
    }

    // ─── /deactivate [name] ───────────────────────────────────────────────────

    public void deactivate(String phone, String term) {
        if (term.isBlank()) { whatsApp.sendText(phone, "Usage: /deactivate [name or phone]"); return; }
        List<Member> results = memberRepo.searchByNameOrPhone(term);
        if (results.isEmpty()) { whatsApp.sendText(phone, "No member found: " + term); return; }
        Member m = results.get(0);
        m.setStatus(Member.MemberStatus.INACTIVE);
        memberRepo.save(m);
        whatsApp.sendText(phone, m.getName() + " has been deactivated.");
    }

    // ─── /leaderboard ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void leaderboard(String phone) {
        LocalDate today = LocalDate.now(zoneId);
        String msg = schedulerService.buildLeaderboard(today.getYear(), today.getMonthValue(), today);
        if (msg == null) {
            whatsApp.sendText(phone, "No attendance data yet this month.");
        } else {
            whatsApp.sendText(phone, msg);
        }
    }

    // ─── /capacity ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void capacity(String phone) {
        LocalDate today = LocalDate.now(zoneId);
        int isoDay = today.getDayOfWeek().getValue();
        List<GymClass> classes = gymClassRepo.findByDayOfWeekAndIsActiveTrue(isoDay);
        if (classes.isEmpty()) { whatsApp.sendText(phone, "No classes scheduled today."); return; }
        long totalCheckins = attendanceRepo.countPresentOnDate(today);
        StringBuilder sb = new StringBuilder("🏋️ *Today's Capacity*\n\nTotal check-ins: " + totalCheckins + "\n\n");
        for (GymClass gc : classes) {
            sb.append("• ").append(gc.getClassName())
              .append(" ").append(gc.getClassTime()).append("–").append(gc.getClassEndTime())
              .append(" | max ").append(gc.getMaxCapacity()).append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ─── /defaulters ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void defaulters(String phone) {
        List<Member> defaulters = memberRepo.findDefaulters();
        if (defaulters.isEmpty()) { whatsApp.sendText(phone, "No defaulters ✅"); return; }
        StringBuilder sb = new StringBuilder("💰 *Payment Defaulters (" + defaulters.size() + ")*\n\n");
        for (Member m : defaulters)
            sb.append("• ").append(m.getName()).append(" | ").append(m.getPaymentStatus())
              .append(" | Exp: ").append(m.getPlanExpiry()).append("\n");
        whatsApp.sendText(phone, sb.toString().trim());
    }
}
