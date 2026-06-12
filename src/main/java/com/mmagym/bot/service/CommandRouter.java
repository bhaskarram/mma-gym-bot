package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.Member;
import com.mmagym.bot.repository.AdminRepository;
import com.mmagym.bot.repository.MemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final AdminRepository adminRepository;
    private final MemberRepository memberRepository;
    private final AttendanceService attendanceService;
    private final MemberService memberService;
    private final AdminService adminService;
    private final SessionService sessionService;
    private final EngagementService engagementService;
    private final BotMetrics metrics;
    private final WhatsAppClient whatsApp;

    public CommandRouter(AdminRepository adminRepository,
                         MemberRepository memberRepository,
                         AttendanceService attendanceService,
                         MemberService memberService,
                         AdminService adminService,
                         SessionService sessionService,
                         EngagementService engagementService,
                         BotMetrics metrics,
                         WhatsAppClient whatsApp) {
        this.adminRepository   = adminRepository;
        this.memberRepository  = memberRepository;
        this.attendanceService = attendanceService;
        this.memberService     = memberService;
        this.adminService      = adminService;
        this.sessionService    = sessionService;
        this.engagementService = engagementService;
        this.metrics           = metrics;
        this.whatsApp          = whatsApp;
    }

    /** Mask phone for logs: 919876543210 → 91XXXXX3210 */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 6) return "XXXXX";
        return phone.substring(0, 2) + "XXXXX" + phone.substring(phone.length() - 4);
    }

    public void route(String phone, String text) {
        metrics.messageReceived();
        log.info("MSG from={} cmd={}", mask(phone), text);

        // Mid multi-step flow
        if (sessionService.has(phone)) {
            SessionService.Session session = sessionService.get(phone);
            if ("FEEDBACK_PENDING".equals(session.action())) {
                Optional<Member> fm = memberRepository.findByPhone(phone);
                fm.ifPresent(member -> engagementService.handleFeedbackReply(phone, member, text));
                return;
            }
            adminService.continueSession(phone, text);
            return;
        }

        String lower    = text.toLowerCase();
        boolean isAdmin = adminRepository.existsByPhoneAndActiveTrue(phone);

        Optional<Member> memberOpt = memberRepository.findByPhone(phone);

        if (memberOpt.isEmpty() && !isAdmin) {
            log.warn("Unregistered contact from={}", mask(phone));
            whatsApp.sendText(phone, "You're not registered at this gym. Ask your coach to add you.");
            return;
        }

        if (isAdmin) {
            metrics.adminCommand();
            log.info("ADMIN cmd={}", lower.split("\\s+")[0]);
            switch (lower.split("\\s+")[0]) {
                case "/users"       -> adminService.listUsers(phone);
                case "/user"        -> adminService.getUser(phone, arg(text));
                case "/attendance"  -> adminService.memberAttendance(phone, arg(text));
                case "/missed"      -> adminService.missedMembers(phone);
                case "/expiring"    -> adminService.expiringMembers(phone);
                case "/summary"     -> adminService.summary(phone);
                case "/mark"        -> adminService.markAttendance(phone, text);
                case "/addmember"    -> adminService.startAddMember(phone);
                case "/deactivate"   -> adminService.deactivate(phone, arg(text));
                case "/addadmin"     -> adminService.addAdmin(phone, arg(text));
                case "/removeadmin"  -> adminService.removeAdmin(phone, arg(text));
                case "/admins"       -> adminService.listAdmins(phone);
                case "/capacity"    -> adminService.capacity(phone);
                case "/defaulters"  -> adminService.defaulters(phone);
                case "/leaderboard" -> adminService.leaderboard(phone);
                case "/feedback"    -> engagementService.viewFeedback(phone);
                case "/botstats"    -> whatsApp.sendText(phone, metrics.snapshot());
                case "hi", "hello", "hey", "menu", "start" -> whatsApp.sendAdminMenu(phone);
                case "menu_mark"       -> adminService.startMarkPrompt(phone);
                case "menu_attendance" -> adminService.startAttendancePrompt(phone);
                case "menu_renew"      -> adminService.startRenewPrompt(phone);
                default -> {
                    log.warn("Unknown admin command={}", lower.split("\\s+")[0]);
                    whatsApp.sendAdminMenu(phone);
                }
            }
            return;
        }

        routeMemberCommand(phone, lower, text, memberOpt.get());
    }

    private void routeMemberCommand(String phone, String lower, String text, Member member) {
        if (member == null) {
            whatsApp.sendText(phone, "You're not registered. Contact your coach.");
            return;
        }
        metrics.memberCommand();
        switch (lower.split("\\s+")[0]) {
            case "/checkin"   -> attendanceService.checkIn(phone, member);
            case "/mystats"   -> memberService.myStats(phone, member);
            case "/streak"    -> engagementService.myStreak(phone, member);
            case "/weight"    -> engagementService.logWeight(phone, member, text);
            case "/mypayment" -> memberService.myPayment(phone, member);
            case "/schedule"  -> memberService.schedule(phone, text);
            case "/progress"  -> memberService.progress(phone, member, text);
            case "/help", "hi", "hello", "hey", "menu", "start" -> whatsApp.sendMenu(phone);
            default           -> whatsApp.sendMenu(phone);
        }
    }

    private String arg(String text) {
        int idx = text.indexOf(' ');
        return idx >= 0 ? text.substring(idx + 1).trim() : "";
    }
}
