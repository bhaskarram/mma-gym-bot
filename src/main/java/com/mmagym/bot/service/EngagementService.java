package com.mmagym.bot.service;

import com.mmagym.bot.client.WhatsAppClient;
import com.mmagym.bot.model.*;
import com.mmagym.bot.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class EngagementService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM");

    private final ProgressRepository      progressRepo;
    private final ClassFeedbackRepository feedbackRepo;
    private final SessionService          sessionService;
    private final WhatsAppClient          whatsApp;
    private final ZoneId                  zoneId;

    public EngagementService(ProgressRepository progressRepo,
                             ClassFeedbackRepository feedbackRepo,
                             SessionService sessionService,
                             WhatsAppClient whatsApp,
                             @Value("${app.timezone}") String timezone) {
        this.progressRepo   = progressRepo;
        this.feedbackRepo   = feedbackRepo;
        this.sessionService = sessionService;
        this.whatsApp       = whatsApp;
        this.zoneId         = ZoneId.of(timezone);
    }

    // ── /streak ───────────────────────────────────────────────────────────────

    public void myStreak(String phone, Member member) {
        int streak = member.getStreakCount();
        boolean shield = member.isStreakShield();
        if (streak == 0) {
            whatsApp.sendText(phone, "No current streak. Check in today to start one! 💪");
        } else {
            String shieldText = shield ? "\n🛡️ Streak Shield active — one miss won't break it!" : "";
            whatsApp.sendText(phone, "🔥 Current streak: " + streak + " consecutive days!" + shieldText);
        }
    }

    // ── /weight ───────────────────────────────────────────────────────────────

    public void logWeight(String phone, Member member, String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) {
            whatsApp.sendText(phone, "Usage: /weight [kg] or /weight history");
            return;
        }
        if ("history".equalsIgnoreCase(parts[1])) {
            weightHistory(phone, member);
            return;
        }
        BigDecimal kg;
        try {
            kg = new BigDecimal(parts[1]);
        } catch (NumberFormatException e) {
            whatsApp.sendText(phone, "Enter a valid weight in kg (e.g. /weight 72.5)");
            return;
        }
        if (kg.compareTo(BigDecimal.ONE) < 0 || kg.compareTo(new BigDecimal("300")) > 0) {
            whatsApp.sendText(phone, "Weight must be between 1 and 300 kg.");
            return;
        }

        Optional<Progress> last = progressRepo
                .findTopByMemberAndMetricTypeOrderByLogDateDesc(member, "WEIGHT");

        Progress p = new Progress();
        p.setMember(member);
        p.setLogDate(LocalDate.now(zoneId));
        p.setMetricType("WEIGHT");
        p.setValue(kg);
        p.setWeightKg(kg);
        progressRepo.save(p);

        if (last.isEmpty()) {
            whatsApp.sendText(phone, "⚖️ Logged " + kg + " kg as your starting weight. Keep showing up!");
        } else {
            BigDecimal prev  = last.get().getValue();
            BigDecimal delta = kg.subtract(prev).setScale(1, RoundingMode.HALF_UP);
            String arrow = delta.compareTo(BigDecimal.ZERO) < 0 ? "📉" : delta.compareTo(BigDecimal.ZERO) > 0 ? "📈" : "";
            String change = delta.compareTo(BigDecimal.ZERO) == 0
                    ? "holding steady 💪"
                    : (delta.compareTo(BigDecimal.ZERO) > 0 ? "+" : "") + delta + " kg since last entry " + arrow;
            whatsApp.sendText(phone, "⚖️ Logged " + kg + " kg — " + change);
        }
    }

    private void weightHistory(String phone, Member member) {
        List<Progress> entries = progressRepo
                .findTop5ByMemberAndMetricTypeOrderByLogDateDesc(member, "WEIGHT");
        if (entries.isEmpty()) {
            whatsApp.sendText(phone, "No weight entries yet. Use /weight 72.5 to log your first.");
            return;
        }
        StringBuilder sb = new StringBuilder("⚖️ *Your weight history:*\n\n");
        for (Progress p : entries) {
            sb.append(p.getLogDate().format(DATE_FMT))
              .append(" — ").append(p.getValue()).append(" kg\n");
        }
        whatsApp.sendText(phone, sb.toString().trim());
    }

    // ── Feedback poll ─────────────────────────────────────────────────────────

    public void sendFeedbackPoll(String phone) {
        sessionService.put(phone, "FEEDBACK_PENDING");
        whatsApp.sendFeedbackButtons(phone);
    }

    public void handleFeedbackReply(String phone, Member member, String text) {
        // Accepts list_reply id (FB_5, FB_4 ...) or plain number (1–5)
        int rating;
        String t = text.trim().toUpperCase();
        if (t.startsWith("FB_")) {
            try { rating = Integer.parseInt(t.substring(3)); }
            catch (NumberFormatException e) { sessionService.clear(phone); return; }
        } else {
            try { rating = Integer.parseInt(t); }
            catch (NumberFormatException e) { sessionService.clear(phone); return; }
        }
        if (rating < 1 || rating > 5) { sessionService.clear(phone); return; }

        ClassFeedback fb = new ClassFeedback();
        fb.setMember(member);
        fb.setClassDate(LocalDate.now(zoneId));
        fb.setRating(rating);
        feedbackRepo.save(fb);
        sessionService.clear(phone);

        String[] thanks = {
            "", // index 0 unused
            "Tough day — but you showed up! That's what counts 💪",
            "Thanks! Keep pushing 🥊",
            "Glad it was a good one! See you next time 👊",
            "Great session! You're on fire 🔥",
            "Excellent! You're crushing it ⭐⭐⭐⭐⭐"
        };
        whatsApp.sendText(phone, "Thanks for the feedback! " + thanks[rating]);
    }

    // ── /feedback (admin) ─────────────────────────────────────────────────────

    public void viewFeedback(String phone) {
        LocalDate now = LocalDate.now(zoneId);
        List<Object[]> rows = feedbackRepo.avgRatingByClassTypeForMonth(now.getYear(), now.getMonthValue());

        // Also get total count and overall average this month
        long total = feedbackRepo.countByMonth(now.getYear(), now.getMonthValue());
        if (total == 0) {
            whatsApp.sendText(phone, "No feedback received this month yet.");
            return;
        }

        String monthName = now.getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);

        StringBuilder sb = new StringBuilder("⭐ *Feedback Report — " + monthName + "*\n\n");

        if (!rows.isEmpty()) {
            for (Object[] row : rows) {
                String classType = row[0] != null ? row[0].toString() : "General";
                double avg = ((Number) row[1]).doubleValue();
                long   cnt = ((Number) row[2]).longValue();
                sb.append("📌 *").append(classType).append("*\n");
                sb.append("   ").append(starsFor(avg)).append(" ").append(String.format("%.1f", avg)).append("/5");
                sb.append(" (").append(cnt).append(" ratings)\n\n");
            }
        }

        sb.append("📊 Total responses: ").append(total);
        whatsApp.sendText(phone, sb.toString().trim());
    }

    private String starsFor(double avg) {
        int full = (int) Math.round(avg);
        return "⭐".repeat(Math.max(0, Math.min(5, full))) +
               "☆".repeat(Math.max(0, 5 - Math.min(5, full)));
    }
}
