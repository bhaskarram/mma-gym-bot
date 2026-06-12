package com.mmagym.bot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmagym.bot.dto.WebhookPayload;
import com.mmagym.bot.service.CommandRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final CommandRouter commandRouter;
    private final String verifyToken;
    private final String appSecret;
    private final ObjectMapper objectMapper;

    public WebhookController(CommandRouter commandRouter,
                             @Value("${meta.verify-token}") String verifyToken,
                             @Value("${meta.app-secret}")   String appSecret,
                             ObjectMapper objectMapper) {
        this.commandRouter = commandRouter;
        this.verifyToken   = verifyToken;
        this.appSecret     = appSecret;
        this.objectMapper  = objectMapper;
    }

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode")         String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge")    String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("Webhook verified by Meta");
            return ResponseEntity.ok(challenge);
        }
        log.warn("Webhook verification failed — token mismatch");
        return ResponseEntity.status(403).body("Forbidden");
    }

    @PostMapping
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody) {

        // 1. Validate signature BEFORE parsing — drop invalid requests immediately
        if (!isValidSignature(signature, rawBody)) {
            log.warn("Invalid X-Hub-Signature-256 — dropping request");
            return ResponseEntity.status(403).body("Forbidden");
        }

        // 2. Parse payload
        WebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse webhook payload", e);
            return ResponseEntity.ok("OK");
        }

        // 3. Return 200 immediately — process async so Meta doesn't retry on slow commands
        processAsync(payload);
        return ResponseEntity.ok("OK");
    }

    /** Processes webhook payload on a background thread — keeps HTTP response fast. */
    @Async("webhookExecutor")
    public void processAsync(WebhookPayload payload) {
        if (payload.entry() == null) return;
        for (WebhookPayload.Entry entry : payload.entry()) {
            if (entry.changes() == null) continue;
            for (WebhookPayload.Change change : entry.changes()) {
                if (change.value() == null) continue;
                List<WebhookPayload.Message> messages = change.value().messages();
                if (messages == null) continue;
                for (WebhookPayload.Message msg : messages) {
                    try {
                        String command = extractCommand(msg);
                        if (command != null) commandRouter.route(msg.from(), command);
                    } catch (Exception e) {
                        log.error("Error processing message from={}", msg.from(), e);
                    }
                }
            }
        }
    }

    /** Extracts the routable command string from any message type. Returns null to skip. */
    private String extractCommand(WebhookPayload.Message msg) {
        if ("text".equals(msg.type()) && msg.text() != null) {
            return msg.text().body().trim();
        }
        if ("interactive".equals(msg.type()) && msg.interactive() != null) {
            WebhookPayload.Interactive ia = msg.interactive();
            if ("list_reply".equals(ia.type()) && ia.listReply() != null) {
                return ia.listReply().id();
            }
            if ("button_reply".equals(ia.type()) && ia.buttonReply() != null) {
                return ia.buttonReply().id();
            }
        }
        return null;
    }

    /** FIX: constant-time comparison prevents timing-based signature bypass attacks. */
    private boolean isValidSignature(String signature, String body) {
        if (signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            // Constant-time comparison — prevents timing attack
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }
}
