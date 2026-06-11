package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.Member;
import com.mmagym.bot.repository.MemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final String adminPhone;
    private final MemberRepository memberRepository;
    private final AttendanceService attendanceService;
    private final MemberService memberService;
    private final AdminService adminService;
    private final SessionService sessionService;
    private final WhatsAppClient whatsApp;

    public CommandRouter(@Value("${app.admin-phone}") String adminPhone,
                         MemberRepository memberRepository,
                         AttendanceService attendanceService,
                         MemberService memberService,
                         AdminService adminService,
                         SessionService sessionService,
                         WhatsAppClient whatsApp) {
        this.adminPhone        = adminPhone;
        this.memberRepository  = memberRepository;
        this.attendanceService = attendanceService;
        this.memberService     = memberService;
        this.adminService      = adminService;
        this.sessionService    = sessionService;
        this.whatsApp          = whatsApp;
    }

    public void route(String phone, String text) {
        log.info("Message from {}: {}", phone, text);

        // If we're mid multi-step flow, continue it
        if (sessionService.has(phone)) {
            adminService.continueSession(phone, text);
            return;
        }

        String lower   = text.toLowerCase();
        boolean isAdmin = adminPhone.equals(phone);

        Optional<Member> memberOpt = memberRepository.findByPhone(phone);

        if (memberOpt.isEmpty() && !isAdmin) {
            whatsApp.sendText(phone,
                    "You're not registered at this gym. Ask your coach to add you.");
            return;
        }

        // Admin-only commands
        if (isAdmin) {
            switch (lower.split("\\s+")[0]) {
                case "/users"      -> adminService.listUsers(phone);
                case "/user"       -> adminService.getUser(phone, arg(text));
                case "/attendance" -> adminService.memberAttendance(phone, arg(text));
                case "/missed"     -> adminService.missedMembers(phone);
                case "/expiring"   -> adminService.expiringMembers(phone);
                case "/summary"    -> adminService.summary(phone);
                case "/mark"       -> adminService.markAttendance(phone, text);
                case "/promote"    -> adminService.promote(phone, text);
                case "/addmember"  -> adminService.startAddMember(phone);
                case "/deactivate" -> adminService.deactivate(phone, arg(text));
                case "/capacity"   -> adminService.capacity(phone);
                case "/defaulters" -> adminService.defaulters(phone);
                default -> routeMemberCommand(phone, lower, text, memberOpt.orElse(null));
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
        switch (lower.split("\\s+")[0]) {
            case "/checkin"        -> attendanceService.checkIn(phone, member);
            case "/mystats"        -> memberService.myStats(phone, member);
            case "/streak"         -> memberService.myStreak(phone, member);
            case "/mypayment"      -> memberService.myPayment(phone, member);
            case "/myrank"         -> memberService.myRank(phone, member);
            case "/schedule"       -> memberService.schedule(phone);
            case "/progress"       -> memberService.progress(phone, member, text);
            case "/help"           -> memberService.help(phone);
            default                -> whatsApp.sendText(phone, "Unknown command. Send /help for the list.");
        }
    }

    private String arg(String text) {
        int idx = text.indexOf(' ');
        return idx >= 0 ? text.substring(idx + 1).trim() : "";
    }
}
