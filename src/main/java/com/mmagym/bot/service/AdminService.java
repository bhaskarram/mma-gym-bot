package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.*;
import com.mmagym.bot.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminService {

    private final MemberRepository     memberRepo;
    private final AttendanceRepository attendanceRepo;
    private final GymClassRepository   gymClassRepo;
    private final AttendanceService    attendanceService;
    private final SessionService       sessionService;
    private final WhatsAppClient       whatsApp;
    private final ZoneId               zoneId;

    public AdminService(MemberRepository memberRepo,
                        AttendanceRepository attendanceRepo,
                        GymClassRepository gymClassRepo,
                        AttendanceService attendanceService,
                        SessionService sessionService,
                        WhatsAppClient whatsApp,
                        @Value("${app.timezone}") String timezone) {
        this.memberRepo       = memberRepo;
        this.attendanceRepo   = attendanceRepo;
        this.gymClassRepo     = gymClassRepo;
        this.attendanceService = attendanceService;
        this.sessionService   = sessionService;
        this.whatsApp         = whatsApp;
        this.zoneId           = ZoneId.of(timezone);
    }

    // ─── /users ──────────────────────────────────────────────────────────────

    public void listUsers(String phone) {
        List<Member> members = memberRepo.findByStatus(Member.MemberStatus.ACTIVE);
        if (members.isEmpty()) {
            whatsApp.sendText(phone, "No active members.");
            return;
        }
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
                "🥋 Belt: " + m.getBeltRank() + "\n" +
                "📦 Plan: " + m.getPlanType() + " | Exp: " + m.getPlanExpiry() + "\n" +
                "💳 Payment: " + m.getPaymentStatus() + "\n" +
                "📅 Status: " + m.getStatus() + "\n" +
                "📊 This month: " + monthAttendance + " days\n" +
                (m.getInjuries() != null ? "🩹 Injuries: " + m.getInjuries() + "\n" : ""));
    }

    // ─── /attendance [name] ───────────────────────────────────────────────────

    public void memberAttendance(String phone, String term) {
        if (term.isBlank()) { whatsApp.sendText(phone, "Usage: /attendance [name or phone]"); return; }
        List<Member> results = memberRepo.searchByNameOrPhone(term);
        if (results.isEmpty()) { whatsApp.sendText(phone, "No member found for: " + term); return; }
        Member m   = results.get(0);
        LocalDate now  = LocalDate.now(zoneId);
        LocalDate from = now.withDayOfMonth(1);
        List<Attendance> records = attendanceRepo
                .findByMemberAndClassDateBetweenOrderByClassDateAsc(m, from, now);
        if (records.isEmpty()) {
            whatsApp.sendText(phone, m.getName() + " has no attendance this month.");
            return;
        }
        StringBuilder sb = new StringBuilder("📅 *" + m.getName() + " — " + now.getMonth() + "*\n\n");
        for (Attendance a : records) {
            String icon = switch (a.getStatus()) {
                case PRESENT -> "✅";
                case ABSENT  -> "❌";
                case LATE    -> "⏰";
            };
            sb.append(icon).append(" ").append(a.getClassDate()).append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ─── /missed ──────────────────────────────────────────────────────────────

    public void missedMembers(String phone) {
        LocalDate today = LocalDate.now(zoneId);
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
                // Find last seen date
                List<Attendance> all = attendanceRepo.findAllByMemberOrderByDateDesc(m);
                String lastSeen = all.stream()
                        .filter(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT)
                        .findFirst()
                        .map(a -> a.getClassDate().toString())
                        .orElse("never");
                count++;
                sb.append(count).append(". ").append(m.getName())
                  .append(" — last seen ").append(lastSeen).append("\n");
            }
        }
        if (count == 0) {
            whatsApp.sendText(phone, "All members attended in the last 5 days ✅");
        } else {
            sb.append("\nSend /mark [name] P to override.");
            whatsApp.sendText(phone, sb.toString().trim());
        }
    }

    // ─── /expiring ────────────────────────────────────────────────────────────

    public void expiringMembers(String phone) {
        LocalDate today = LocalDate.now(zoneId);
        List<Member> expiring = memberRepo.findExpiringBetween(today, today.plusDays(7));
        if (expiring.isEmpty()) {
            whatsApp.sendText(phone, "No plans expiring in the next 7 days ✅");
            return;
        }
        StringBuilder sb = new StringBuilder("⏰ *Expiring in 7 days (" + expiring.size() + ")*\n\n");
        for (Member m : expiring) {
            sb.append("• ").append(m.getName())
              .append(" — expires ").append(m.getPlanExpiry()).append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ─── /summary ────────────────────────────────────────────────────────────

    public void summary(String phone) {
        List<Member> all    = memberRepo.findAll();
        long active         = all.stream().filter(m -> m.getStatus() == Member.MemberStatus.ACTIVE).count();
        long expired        = all.stream().filter(m -> m.getPaymentStatus() == Member.PaymentStatus.EXPIRED).count();
        LocalDate today     = LocalDate.now(zoneId);
        long todayCheckins  = attendanceRepo.countPresentOnDate(today);
        whatsApp.sendText(phone,
                "📊 *Gym Summary*\n\n" +
                "👥 Active members: " + active + "\n" +
                "⚠️  Expired plans: " + expired + "\n" +
                "✅ Today's check-ins: " + todayCheckins);
    }

    // ─── /mark [name] [P/A/L] ────────────────────────────────────────────────

    public void markAttendance(String phone, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 3) {
            whatsApp.sendText(phone, "Usage: /mark [name] [P/A/L]");
            return;
        }
        String name = parts[1];
        String flag = parts[2].toUpperCase();
        Attendance.AttendanceStatus status = switch (flag) {
            case "P" -> Attendance.AttendanceStatus.PRESENT;
            case "A" -> Attendance.AttendanceStatus.ABSENT;
            case "L" -> Attendance.AttendanceStatus.LATE;
            default  -> null;
        };
        if (status == null) { whatsApp.sendText(phone, "Flag must be P, A, or L."); return; }

        List<Member> results = memberRepo.searchByNameOrPhone(name);
        if (results.isEmpty()) { whatsApp.sendText(phone, "No member found: " + name); return; }

        Member m = results.get(0);
        attendanceService.adminMarkAttendance(phone, m, LocalDate.now(zoneId), status);
        whatsApp.sendText(phone, "Marked " + m.getName() + " as " + status + " for today ✅");
    }

    // ─── /promote [name] [belt] ───────────────────────────────────────────────

    public void promote(String phone, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 3) {
            whatsApp.sendText(phone, "Usage: /promote [name] [WHITE/BLUE/PURPLE/BROWN/BLACK]");
            return;
        }
        List<Member> results = memberRepo.searchByNameOrPhone(parts[1]);
        if (results.isEmpty()) { whatsApp.sendText(phone, "No member found: " + parts[1]); return; }
        Member m = results.get(0);
        try {
            Member.BeltRank newRank = Member.BeltRank.valueOf(parts[2].toUpperCase());
            m.setBeltRank(newRank);
            memberRepo.save(m);
            whatsApp.sendText(phone, m.getName() + " promoted to " + newRank + " belt ✅");
            whatsApp.sendText(m.getPhone(),
                    "Congratulations " + m.getName() + "! You've been promoted to " + newRank + " belt! 🏆🥋");
        } catch (IllegalArgumentException e) {
            whatsApp.sendText(phone, "Unknown belt: " + parts[2] + ". Options: WHITE BLUE PURPLE BROWN BLACK");
        }
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
                sessionService.put(phone, "ADD_MEMBER_PHONE", Map.of("name", text));
                whatsApp.sendText(phone, "Enter phone (with country code, e.g. 919876543210):");
            }
            case "ADD_MEMBER_PHONE" -> {
                sessionService.updateData(phone, "memberPhone", text);
                session.data().put("memberPhone", text);
                sessionService.put(phone, "ADD_MEMBER_PLAN", session.data());
                whatsApp.sendText(phone, "Select plan:\n1. Monthly\n2. Quarterly\n3. Annual");
            }
            case "ADD_MEMBER_PLAN" -> {
                Member.PlanType planType = switch (text.trim()) {
                    case "1" -> Member.PlanType.MONTHLY;
                    case "2" -> Member.PlanType.QUARTERLY;
                    case "3" -> Member.PlanType.ANNUAL;
                    default  -> null;
                };
                if (planType == null) {
                    whatsApp.sendText(phone, "Please enter 1, 2, or 3.");
                    return;
                }
                String name        = session.data().get("name");
                String memberPhone = session.data().get("memberPhone");
                LocalDate today    = LocalDate.now(zoneId);
                LocalDate expiry   = planType.calculateExpiry(today);

                Member m = new Member();
                m.setName(name);
                m.setPhone(memberPhone);
                m.setPlanType(planType);
                m.setJoinDate(today);
                m.setPlanExpiry(expiry);
                m.setPaymentStatus(Member.PaymentStatus.PAID);
                m.setStatus(Member.MemberStatus.ACTIVE);
                memberRepo.save(m);

                sessionService.clear(phone);
                whatsApp.sendText(phone,
                        "Member registered ✅\n" + name + " | " + planType + " | Expires: " + expiry);
                whatsApp.sendText(memberPhone,
                        "Welcome to the gym, " + name + "! 🥊\nSend /help to see all available commands.");
            }
            default -> sessionService.clear(phone);
        }
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

    // ─── /capacity ────────────────────────────────────────────────────────────

    public void capacity(String phone) {
        LocalDate today   = LocalDate.now(zoneId);
        int isoDay        = today.getDayOfWeek().getValue();
        List<GymClass> classes = gymClassRepo.findByDayOfWeekAndIsActiveTrue(isoDay);
        if (classes.isEmpty()) { whatsApp.sendText(phone, "No classes scheduled today."); return; }
        long totalCheckins = attendanceRepo.countPresentOnDate(today);
        StringBuilder sb = new StringBuilder("🏋️ *Today's Capacity*\n\n");
        sb.append("Total check-ins so far: ").append(totalCheckins).append("\n\n");
        for (GymClass gc : classes) {
            sb.append("• ").append(gc.getClassName())
              .append(" @ ").append(gc.getClassTime())
              .append(" — max ").append(gc.getMaxCapacity()).append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ─── /defaulters ─────────────────────────────────────────────────────────

    public void defaulters(String phone) {
        List<Member> defaulters = memberRepo.findDefaulters();
        if (defaulters.isEmpty()) { whatsApp.sendText(phone, "No defaulters ✅"); return; }
        StringBuilder sb = new StringBuilder("💰 *Payment Defaulters (" + defaulters.size() + ")*\n\n");
        for (Member m : defaulters) {
            sb.append("• ").append(m.getName())
              .append(" | ").append(m.getPaymentStatus())
              .append(" | Exp: ").append(m.getPlanExpiry()).append("\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }
}
