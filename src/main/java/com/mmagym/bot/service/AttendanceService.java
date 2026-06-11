package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.Attendance;
import com.mmagym.bot.model.GymClass;
import com.mmagym.bot.model.Member;
import com.mmagym.bot.repository.AttendanceRepository;
import com.mmagym.bot.repository.GymClassRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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

    private final AttendanceRepository attendanceRepo;
    private final GymClassRepository gymClassRepo;
    private final WhatsAppClient whatsApp;
    private final ZoneId zoneId;
    private final int windowMinutes;

    public AttendanceService(AttendanceRepository attendanceRepo,
                             GymClassRepository gymClassRepo,
                             WhatsAppClient whatsApp,
                             @Value("${app.timezone}") String timezone,
                             @Value("${app.checkin.window-minutes}") int windowMinutes) {
        this.attendanceRepo = attendanceRepo;
        this.gymClassRepo   = gymClassRepo;
        this.whatsApp       = whatsApp;
        this.zoneId         = ZoneId.of(timezone);
        this.windowMinutes  = windowMinutes;
    }

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

        boolean withinWindow = todaysClasses.stream().anyMatch(gc ->
                !nowTime.isBefore(gc.getClassTime().minusMinutes(windowMinutes)) &&
                !nowTime.isAfter(gc.getClassTime().plusMinutes(windowMinutes)));

        if (!withinWindow) {
            String nextTime = todaysClasses.stream()
                    .map(gc -> gc.getClassTime().toString())
                    .findFirst()
                    .orElse("scheduled class time");
            whatsApp.sendText(phone,
                    "Check-in window not open. Next class starts at " + nextTime + ".");
            return;
        }

        if (attendanceRepo.existsByMemberAndClassDate(member, today)) {
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
        } catch (DataIntegrityViolationException e) {
            // Race condition — duplicate, treat as already checked in
            whatsApp.sendText(phone, "Already checked in today ✅");
            return;
        }

        long monthCount = attendanceRepo.countPresentInMonth(
                member, today.getYear(), today.getMonthValue());
        long streak = computeStreak(member);

        whatsApp.sendText(phone,
                "Checked in ✅ " + today.format(DATE_FMT) +
                " — " + monthCount + " days this month." +
                (streak > 1 ? " Streak: " + streak + " days 🔥" : ""));
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

    public long computeStreak(Member member) {
        List<Attendance> records = attendanceRepo.findAllByMemberOrderByDateDesc(member);
        if (records.isEmpty()) return 0;

        LocalDate expected = LocalDate.now(zoneId);
        long streak = 0;
        for (Attendance a : records) {
            if (a.getClassDate().equals(expected) && a.getStatus() == Attendance.AttendanceStatus.PRESENT) {
                streak++;
                expected = expected.minusDays(1);
            } else if (a.getClassDate().isBefore(expected)) {
                break;
            }
        }
        return streak;
    }
}
