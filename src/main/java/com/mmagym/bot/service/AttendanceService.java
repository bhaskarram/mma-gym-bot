package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.Attendance;
import com.mmagym.bot.model.GymClass;
import com.mmagym.bot.model.Member;
import com.mmagym.bot.model.MilestoneAward;
import com.mmagym.bot.repository.AttendanceRepository;
import com.mmagym.bot.repository.GymClassRepository;
import com.mmagym.bot.repository.MilestoneAwardRepository;
import com.mmagym.bot.repository.MemberRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AttendanceService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM");
    private static final int SHIELD_THRESHOLD = 21;
    private static final List<Integer> MILESTONES = List.of(10, 25, 50, 100);

    private final AttendanceRepository attendanceRepo;
    private final GymClassRepository gymClassRepo;
    private final MilestoneAwardRepository milestoneRepo;
    private final MemberRepository         memberRepo;
    private final EngagementService        engagementService;
    private final BotMetrics               metrics;
    private final WhatsAppClient whatsApp;
    private final ZoneId zoneId;
    private final int windowMinutes;

    public AttendanceService(AttendanceRepository attendanceRepo,
                             GymClassRepository gymClassRepo,
                             MilestoneAwardRepository milestoneRepo,
                             MemberRepository memberRepo,
                             EngagementService engagementService,
                             BotMetrics metrics,
                             WhatsAppClient whatsApp,
                             @Value("${app.timezone}") String timezone,
                             @Value("${app.checkin.window-minutes}") int windowMinutes) {
        this.attendanceRepo    = attendanceRepo;
        this.gymClassRepo      = gymClassRepo;
        this.milestoneRepo     = milestoneRepo;
        this.memberRepo        = memberRepo;
        this.engagementService = engagementService;
        this.metrics           = metrics;
        this.whatsApp          = whatsApp;
        this.zoneId            = ZoneId.of(timezone);
        this.windowMinutes     = windowMinutes;
    }

    @Transactional
    public void checkIn(String phone, Member member) {
        if (member.getStatus() != Member.MemberStatus.ACTIVE ||
            member.getPaymentStatus() != Member.PaymentStatus.PAID) {
            whatsApp.sendText(phone,
                    "Your plan has expired or your account is inactive. Contact admin to renew.");
            return;
        }

        ZonedDateTime now   = ZonedDateTime.now(zoneId);
        LocalDate today     = now.toLocalDate();
        LocalTime nowTime   = now.toLocalTime();

        // ISO day of week: Monday=1, Sunday=7
        int isoDay = today.getDayOfWeek().getValue();
        List<GymClass> todaysClasses = gymClassRepo.findByDayOfWeekAndIsActiveTrue(isoDay);

        boolean withinWindow = todaysClasses.stream().anyMatch(gc -> {
            LocalTime start = gc.getClassTime().minusMinutes(windowMinutes);
            LocalTime end   = gc.getClassEndTime() != null
                    ? gc.getClassEndTime()
                    : gc.getClassTime().plusMinutes(windowMinutes);
            return !nowTime.isBefore(start) && !nowTime.isAfter(end);
        });

        if (!withinWindow) {
            String nextTime = todaysClasses.stream()
                    .filter(gc -> nowTime.isBefore(gc.getClassTime()))
                    .map(gc -> gc.getClassTime().toString())
                    .findFirst()
                    .orElse("scheduled class time");
            whatsApp.sendText(phone,
                    "No open class right now. Next class at " + nextTime + ".");
            return;
        }

        if (attendanceRepo.existsByMemberAndClassDate(member, today)) {
            metrics.checkInBlocked();
            whatsApp.sendText(phone, "Already checked in today ✅");
            return;
        }

        Attendance a = new Attendance();
        a.setMember(member);
        a.setClassDate(today);
        a.setStatus(Attendance.AttendanceStatus.PRESENT);
        a.setMarkedBy(Attendance.MarkedBy.SELF);

        try {
            attendanceRepo.save(a);
            metrics.checkInSuccess();
        } catch (DataIntegrityViolationException e) {
            metrics.checkInBlocked();
            whatsApp.sendText(phone, "Already checked in today ✅");
            return;
        }

        // Update streak
        updateStreak(phone, member, today);
        memberRepo.save(member);

        long monthCount = attendanceRepo.countPresentInMonth(member, today.getYear(), today.getMonthValue());
        int streak = member.getStreakCount();

        whatsApp.sendText(phone,
                "Checked in ✅ " + today.format(DATE_FMT) +
                " — " + monthCount + " days this month." +
                (streak > 1 ? " Streak: " + streak + " days 🔥" : "") +
                (member.isStreakShield() ? " 🛡️" : ""));

        // Check milestones
        checkMilestones(phone, member);

        // Trigger feedback poll
        engagementService.sendFeedbackPoll(phone);
    }

    public void adminMarkAttendance(String adminPhone, Member member, LocalDate date,
                                    Attendance.AttendanceStatus statusToSet) {
        Attendance a = attendanceRepo.findByMemberAndClassDate(member, date)
                .orElseGet(() -> {
                    Attendance n = new Attendance();
                    n.setMember(member);
                    n.setClassDate(date);
                    return n;
                });
        a.setStatus(statusToSet);
        a.setMarkedBy(Attendance.MarkedBy.ADMIN);
        attendanceRepo.save(a);
    }

    private void updateStreak(String phone, Member member, LocalDate today) {
        LocalDate last = member.getLastCheckinDate();
        if (last == null) {
            member.setStreakCount(1);
        } else {
            long classDaysBetween = last.plusDays(1).datesUntil(today)
                    .filter(d -> d.getDayOfWeek().getValue() <= 6)
                    .count();
            if (classDaysBetween == 0) {
                member.setStreakCount(member.getStreakCount() + 1);
            } else if (classDaysBetween == 1 && member.isStreakShield()) {
                member.setStreakShield(false);
                member.setStreakCount(member.getStreakCount() + 1);
                whatsApp.sendText(phone, "🛡️ Shield used — streak saved! Keep going 💪");
            } else {
                member.setStreakCount(1);
                member.setStreakShield(false);
            }
        }
        member.setLastCheckinDate(today);
        if (member.getStreakCount() >= SHIELD_THRESHOLD && !member.isStreakShield()) {
            member.setStreakShield(true);
            whatsApp.sendText(phone, "🛡️ You've earned a Streak Shield! Miss one class day without breaking your streak.");
        }
    }

    private void checkMilestones(String phone, Member member) {
        long total = attendanceRepo.countAllPresentForMember(member);
        for (int milestone : MILESTONES) {
            if (total >= milestone && !milestoneRepo.existsByMemberAndMilestone(member, milestone)) {
                MilestoneAward award = new MilestoneAward();
                award.setMember(member);
                award.setMilestone(milestone);
                milestoneRepo.save(award);
                whatsApp.sendText(phone,
                        "🎖️ " + milestone + " classes! You're a warrior, " + member.getName() + ". Keep grinding 🥊");
                broadcastMilestone(phone, member, milestone);
            }
        }
    }

    private void broadcastMilestone(String selfPhone, Member member, int milestone) {
        List<Member> active = memberRepo.findActivePaidMembers();
        String msg = "🎖️ " + member.getName() + " just hit " + milestone + " classes at the gym!\nShow them some love 💪";
        for (Member m : active) {
            if (!m.getPhone().equals(selfPhone)) {
                whatsApp.sendText(m.getPhone(), msg);
            }
        }
    }

    public long computeStreak(Member member) {
        List<Attendance> records = attendanceRepo.findAllByMemberOrderByDateDesc(member);
        if (records.isEmpty()) return 0;

        LocalDate expected = LocalDate.now(zoneId);
        // Skip Sundays — no class on Sunday so they don't break streaks
        while (expected.getDayOfWeek() == DayOfWeek.SUNDAY) {
            expected = expected.minusDays(1);
        }

        long streak = 0;
        for (Attendance a : records) {
            if (a.getClassDate().equals(expected) && a.getStatus() == Attendance.AttendanceStatus.PRESENT) {
                streak++;
                expected = expected.minusDays(1);
                // Skip Sundays when stepping backward
                while (expected.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    expected = expected.minusDays(1);
                }
            } else if (a.getClassDate().isBefore(expected)) {
                break;
            }
        }
        return streak;
    }
}
