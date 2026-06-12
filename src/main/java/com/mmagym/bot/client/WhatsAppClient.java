package com.mmagym.bot.client;

import com.mmagym.bot.service.BotMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class WhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppClient.class);

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String phoneNumberId;
    private final String accessToken;
    private final BotMetrics metrics;

    public WhatsAppClient(RestTemplate restTemplate,
                          @Value("${meta.api-url}")          String apiUrl,
                          @Value("${meta.phone-number-id}") String phoneNumberId,
                          @Value("${meta.access-token}")    String accessToken,
                          BotMetrics metrics) {
        this.restTemplate  = restTemplate;
        this.apiUrl        = apiUrl;
        this.phoneNumberId = phoneNumberId;
        this.accessToken   = accessToken;
        this.metrics       = metrics;
    }

    private void post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                metrics.whatsappSent();
            } else {
                metrics.whatsappError();
                log.error("Meta API {} — {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            metrics.whatsappError();
            log.error("WhatsApp send failed", e);
        }
    }

    public void sendText(String toPhone, String message) {
        post(apiUrl + "/" + phoneNumberId + "/messages", Map.of(
                "messaging_product", "whatsapp",
                "recipient_type",    "individual",
                "to",                toPhone,
                "type",              "text",
                "text",              Map.of("body", message)
        ));
    }

    // ── Feedback buttons (list — 5 rows) ──────────────────────────────────────
    public void sendFeedbackButtons(String toPhone) {
        Map<String, Object> body = Map.of(
            "messaging_product", "whatsapp",
            "recipient_type",    "individual",
            "to",                toPhone,
            "type",              "interactive",
            "interactive", Map.of(
                "type",   "list",
                "header", Map.of("type", "text", "text", "🥊 How was today's session?"),
                "body",   Map.of("text", "Rate your class — your feedback helps us improve!"),
                "footer", Map.of("text", "Takes 2 seconds 👇"),
                "action", Map.of(
                    "button", "⭐ Rate Now",
                    "sections", List.of(
                        Map.of("title", "Your Rating", "rows", List.of(
                            Map.of("id", "FB_5", "title", "⭐⭐⭐⭐⭐  Excellent!", "description", "Best class ever 🔥"),
                            Map.of("id", "FB_4", "title", "⭐⭐⭐⭐  Great",       "description", "Really enjoyed it 💪"),
                            Map.of("id", "FB_3", "title", "⭐⭐⭐  Good",         "description", "Solid session 👍"),
                            Map.of("id", "FB_2", "title", "⭐⭐  Average",        "description", "Could be better 😐"),
                            Map.of("id", "FB_1", "title", "⭐  Tough",           "description", "Really hard today 😮‍💨")
                        ))
                    )
                )
            )
        );

        post(apiUrl + "/" + phoneNumberId + "/messages", body);
    }

    public void sendPlanPicker(String toPhone) {
        post(apiUrl + "/" + phoneNumberId + "/messages", Map.of(
            "messaging_product", "whatsapp",
            "recipient_type",    "individual",
            "to",                toPhone,
            "type",              "interactive",
            "interactive", Map.of(
                "type",   "button",
                "body",   Map.of("text", "Select membership plan:"),
                "action", Map.of("buttons", List.of(
                    Map.of("type", "reply", "reply", Map.of("id", "PLAN_MONTHLY",   "title", "📅 Monthly")),
                    Map.of("type", "reply", "reply", Map.of("id", "PLAN_QUARTERLY", "title", "📆 Quarterly")),
                    Map.of("type", "reply", "reply", Map.of("id", "PLAN_ANNUAL",    "title", "🗓️ Annual"))
                ))
            )
        ));
    }

    public void sendMenu(String toPhone) {
        Map<String, Object> body = Map.of(
            "messaging_product", "whatsapp",
            "recipient_type",    "individual",
            "to",                toPhone,
            "type",              "interactive",
            "interactive", Map.of(
                "type",   "list",
                "header", Map.of("type", "text", "text", "🥊 MMA Gym Bot"),
                "body",   Map.of("text", "Hi! What would you like to do today?"),
                "footer", Map.of("text", "Tap an option to get started"),
                "action", Map.of(
                    "button", "📋 Open Menu",
                    "sections", List.of(
                        Map.of("title", "🏋️ Attendance", "rows", List.of(
                            Map.of("id", "/checkin",       "title", "✅ Check In",        "description", "Mark today's attendance"),
                            Map.of("id", "/mystats",       "title", "📊 My Stats",        "description", "This month's attendance %"),
                            Map.of("id", "/streak",        "title", "🔥 My Streak",       "description", "Streak count & shield status")
                        )),
                        Map.of("title", "📅 Schedule", "rows", List.of(
                            Map.of("id", "/schedule",      "title", "📅 Today's Classes", "description", "Classes happening today"),
                            Map.of("id", "/schedule week", "title", "🗓️ Full Week",       "description", "Complete weekly timetable")
                        )),
                        Map.of("title", "📈 Progress", "rows", List.of(
                            Map.of("id", "/weight",        "title", "⚖️ Log Weight",      "description", "Track your body weight"),
                            Map.of("id", "/weight history","title", "📋 Weight History",  "description", "Last 5 weight entries"),
                            Map.of("id", "/progress view", "title", "📈 View Progress",   "description", "Last 10 training entries")
                        )),
                        Map.of("title", "💳 Account", "rows", List.of(
                            Map.of("id", "/mypayment",     "title", "💳 My Payment",      "description", "Plan type, expiry & days left")
                        ))
                    )
                )
            )
        );
        post(apiUrl + "/" + phoneNumberId + "/messages", body);
    }

    public void sendAdminMenu(String toPhone) {
        Map<String, Object> body = Map.of(
            "messaging_product", "whatsapp",
            "recipient_type",    "individual",
            "to",                toPhone,
            "type",              "interactive",
            "interactive", Map.of(
                "type",   "list",
                "header", Map.of("type", "text", "text", "🛡️ Admin Panel"),
                "body",   Map.of("text", "What would you like to do?"),
                "footer", Map.of("text", "Tap an option to get started"),
                "action", Map.of(
                    "button", "📋 Admin Menu",
                    "sections", List.of(
                        Map.of("title", "👥 Members", "rows", List.of(
                            Map.of("id", "/addmember", "title", "➕ Add Member",     "description", "Register a new member"),
                            Map.of("id", "/users",     "title", "👥 All Members",    "description", "List all registered members"),
                            Map.of("id", "/expiring",  "title", "⏳ Expiring Plans", "description", "Members expiring soon")
                        )),
                        Map.of("title", "📊 Reports", "rows", List.of(
                            Map.of("id", "/summary",     "title", "📊 Gym Summary",      "description", "Overview of gym stats"),
                            Map.of("id", "/leaderboard", "title", "🏆 Leaderboard",      "description", "Top attenders this month"),
                            Map.of("id", "/defaulters",  "title", "💸 Defaulters",       "description", "Unpaid members"),
                            Map.of("id", "/feedback",    "title", "⭐ Feedback Report",  "description", "Avg class ratings this month")
                        )),
                        Map.of("title", "✏️ Manage", "rows", List.of(
                            Map.of("id", "MENU_MARK",       "title", "✅ Mark Attendance", "description", "Mark a member P / A / L"),
                            Map.of("id", "MENU_ATTENDANCE", "title", "📋 Member History",  "description", "View a member's attendance"),
                            Map.of("id", "MENU_RENEW",      "title", "🔄 Renew Plan",      "description", "Extend a member's membership")
                        ))
                    )
                )
            )
        );
        post(apiUrl + "/" + phoneNumberId + "/messages", body);
    }
}
