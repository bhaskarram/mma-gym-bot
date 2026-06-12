package com.mmagym.bot.service;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Central metrics registry for the bot.
 * All counters are in-memory and reset on restart.
 * Exposed at GET /manage/metrics
 */
@Component
public class BotMetrics {

    // ── Counters (total since startup) ────────────────────────────────────────
    private final Counter messagesReceived;
    private final Counter adminCommands;
    private final Counter memberCommands;
    private final Counter checkIns;
    private final Counter checkInBlocked;
    private final Counter whatsappSent;
    private final Counter whatsappErrors;
    private final Counter membersAdded;
    private final Counter renewals;
    private final Counter feedbackReceived;

    // ── Gauge — live session count ────────────────────────────────────────────
    private final AtomicLong activeSessions = new AtomicLong(0);

    public BotMetrics(MeterRegistry registry) {
        messagesReceived = Counter.builder("bot.messages.received")
                .description("Total WhatsApp messages received")
                .register(registry);
        adminCommands    = Counter.builder("bot.commands.admin")
                .description("Admin commands executed")
                .register(registry);
        memberCommands   = Counter.builder("bot.commands.member")
                .description("Member commands executed")
                .register(registry);
        checkIns         = Counter.builder("bot.checkins.success")
                .description("Successful check-ins")
                .register(registry);
        checkInBlocked   = Counter.builder("bot.checkins.blocked")
                .description("Blocked check-in attempts (duplicate/outside window)")
                .register(registry);
        whatsappSent     = Counter.builder("bot.whatsapp.sent")
                .description("WhatsApp messages sent")
                .register(registry);
        whatsappErrors   = Counter.builder("bot.whatsapp.errors")
                .description("WhatsApp send failures")
                .register(registry);
        membersAdded     = Counter.builder("bot.members.added")
                .description("New members registered")
                .register(registry);
        renewals         = Counter.builder("bot.members.renewals")
                .description("Plan renewals completed")
                .register(registry);
        feedbackReceived = Counter.builder("bot.feedback.received")
                .description("Feedback ratings submitted")
                .register(registry);

        Gauge.builder("bot.sessions.active", activeSessions, AtomicLong::get)
                .description("Currently active multi-step sessions")
                .register(registry);
    }

    // ── Increment methods ──────────────────────────────────────────────────────
    public void messageReceived()  { messagesReceived.increment(); }
    public void adminCommand()     { adminCommands.increment(); }
    public void memberCommand()    { memberCommands.increment(); }
    public void checkInSuccess()   { checkIns.increment(); }
    public void checkInBlocked()   { checkInBlocked.increment(); }
    public void whatsappSent()     { whatsappSent.increment(); }
    public void whatsappError()    { whatsappErrors.increment(); }
    public void memberAdded()      { membersAdded.increment(); }
    public void renewal()          { renewals.increment(); }
    public void feedbackReceived() { feedbackReceived.increment(); }
    public void sessionOpened()    { activeSessions.incrementAndGet(); }
    public void sessionClosed()    { activeSessions.decrementAndGet(); }

    // ── Snapshot for admin WhatsApp report ───────────────────────────────────
    public String snapshot() {
        return "📊 *Bot Metrics (since last restart)*\n\n" +
               "📨 Messages received: "  + (long) messagesReceived.count() + "\n" +
               "🛡️ Admin commands: "     + (long) adminCommands.count()    + "\n" +
               "👤 Member commands: "    + (long) memberCommands.count()   + "\n\n" +
               "✅ Check-ins: "          + (long) checkIns.count()         + "\n" +
               "🚫 Blocked check-ins: " + (long) checkInBlocked.count()   + "\n\n" +
               "📤 WhatsApp sent: "      + (long) whatsappSent.count()     + "\n" +
               "❌ Send errors: "        + (long) whatsappErrors.count()   + "\n\n" +
               "➕ Members added: "      + (long) membersAdded.count()     + "\n" +
               "🔄 Renewals: "           + (long) renewals.count()         + "\n" +
               "⭐ Feedback received: "  + (long) feedbackReceived.count() + "\n" +
               "🔁 Active sessions: "    + activeSessions.get();
    }
}
